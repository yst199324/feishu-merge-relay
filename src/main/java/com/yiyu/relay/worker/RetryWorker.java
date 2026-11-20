package com.yiyu.relay.worker;

import com.yiyu.relay.client.ForwardOutcome;
import com.yiyu.relay.client.ForwardResult;
import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.RetryLadder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重试线程：扫 {@code retry/} 里到期的文件 → 转发 → 删文件 / 次数加一 / 进死信。
 *
 * <p>固定 1 个线程，天然保序。核心是两条规则：</p>
 * <ul>
 *   <li><b>让路</b>：待发队列积压到 {@code max-pending-before-yield} 就整轮跳过，
 *       把云效和飞书的调用额度全留给新消息（设计文档 14.7）。这个阈值必须小于
 *       待发队列上限，否则规则永远不触发。</li>
 *   <li><b>次数用尽进死信</b>：阶梯有几个数就是几次重试，第 n 次失败后
 *       {@code n + 1 > 阶梯长度} 就判 exhausted。</li>
 * </ul>
 */
@Component
public class RetryWorker extends AbstractWorker {

    private static final Logger log = LoggerFactory.getLogger(RetryWorker.class);

    private final QueueStore queueStore;
    private final ForwardingService forwardingService;
    private final QueueOutcomeHandler outcomeHandler;
    private final RetryLadder retryLadder;
    private final RelayProperties properties;

    public RetryWorker(RelayProperties properties,
                       QueueStore queueStore,
                       ForwardingService forwardingService,
                       QueueOutcomeHandler outcomeHandler,
                       RetryLadder retryLadder) {
        // 重试线程固定 1 个：多个线程重试没有收益，还会打乱阶梯的时间分布
        super(properties, "重试线程", 1);
        this.properties = properties;
        this.queueStore = queueStore;
        this.forwardingService = forwardingService;
        this.outcomeHandler = outcomeHandler;
        this.retryLadder = retryLadder;
    }

    @Override
    protected int processRound(int partitionIndex, int partitionCount) {
        if (shouldYieldToPending()) {
            return 0;
        }
        List<QueueFile> due = queueStore.listRetryDue(System.currentTimeMillis());
        int batchLimit = properties.getRetry().getBatchPerRound();
        int handled = 0;
        for (int index = 0; index < due.size() && handled < batchLimit; index++) {
            handled++;
            handleOne(due.get(index));
        }
        return handled;
    }

    /** 待发积压超过阈值就让路，本轮一条都不重试。 */
    private boolean shouldYieldToPending() {
        int pendingCount = queueStore.countPending();
        int threshold = properties.getRetry().getMaxPendingBeforeYield();
        if (pendingCount >= threshold) {
            log.debug("待发积压 {} 条（阈值 {}），重试线程本轮让路", pendingCount, threshold);
            return true;
        }
        return false;
    }

    /**
     * 处理一条到期消息。
     *
     * <p>失败分为两类：永久失败（400 / 413、内部入口没开）直接进死信；
     * 可重试失败则算下一次重试次数，用尽阶梯才进死信（原因 exhausted）。</p>
     */
    private void handleOne(QueueFile file) {
        ForwardResult result = forwardingService.forward(file);
        if (result.getOutcome() == ForwardOutcome.SUCCESS) {
            outcomeHandler.deleteAfterSuccess(file);
            return;
        }
        if (result.getOutcome() == ForwardOutcome.RETRYABLE) {
            scheduleNextAttempt(file);
            return;
        }
        outcomeHandler.moveToDead(file, result);
    }

    /** 计算并安排下一次重试；次数超过阶梯长度就判用尽。 */
    private void scheduleNextAttempt(QueueFile file) {
        int nextAttempts = file.getAttempts() + 1;
        if (nextAttempts > retryLadder.maxAttempts()) {
            outcomeHandler.moveToDead(file, QueueStore.REASON_EXHAUSTED,
                    "已重试 " + file.getAttempts() + " 次，阶梯用尽");
            return;
        }
        outcomeHandler.scheduleRetry(file, nextAttempts);
    }
}

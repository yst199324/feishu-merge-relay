package com.yiyu.relay.worker;

import com.yiyu.relay.client.ForwardOutcome;
import com.yiyu.relay.client.ForwardResult;
import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 待发线程：扫 {@code pending/} → 转发 → 删文件 / 进重试。
 *
 * <p>线程数来自 {@code relay.target.threads}。默认 1，按到达顺序处理；
 * 调大后多条并行、不再保序。多线程时不抢同一个文件的做法是"按下标取模分区"，
 * 不需要加锁，也不会两个线程转发同一条。</p>
 */
@Component
public class PendingWorker extends AbstractWorker {

    private final QueueStore queueStore;
    private final ForwardingService forwardingService;
    private final QueueOutcomeHandler outcomeHandler;
    private final RelayProperties properties;

    public PendingWorker(RelayProperties properties,
                         QueueStore queueStore,
                         ForwardingService forwardingService,
                         QueueOutcomeHandler outcomeHandler) {
        super(properties, "待发线程", properties.getTarget().getThreads());
        this.properties = properties;
        this.queueStore = queueStore;
        this.forwardingService = forwardingService;
        this.outcomeHandler = outcomeHandler;
    }

    @Override
    protected int processRound(int partitionIndex, int partitionCount) {
        List<QueueFile> pending = queueStore.listPending();
        // 额度按分区平分：relay.pending.batch-per-round 描述的是"这一组线程每轮最多处理几条"，
        // 若每个分区都按满额处理，调大 threads 会让每轮处理量翻倍，突发恢复时更容易刷屏
        int batchLimit = Math.max(1, properties.getPending().getBatchPerRound() / partitionCount);
        int handled = 0;
        for (int index = partitionIndex; index < pending.size() && handled < batchLimit; index += partitionCount) {
            QueueFile file = pending.get(index);
            handled++;
            handleOne(file);
        }
        return handled;
    }

    /**
     * 处理一条待发消息。
     *
     * <p>待发阶段的第一次失败就是"第 1 次重试"，等阶梯第一档；
     * 是否进死信由错误类型决定，不由次数决定 —— 次数耗尽只发生在 retry 阶段。</p>
     */
    private void handleOne(QueueFile file) {
        ForwardResult result = forwardingService.forward(file);
        if (result.getOutcome() == ForwardOutcome.SUCCESS) {
            outcomeHandler.deleteAfterSuccess(file);
            return;
        }
        if (result.getOutcome() == ForwardOutcome.RETRYABLE) {
            outcomeHandler.scheduleRetry(file, 1);
            return;
        }
        // PERMANENT / ENDPOINT_MISSING：重试再多次也没用，直接进死信
        outcomeHandler.moveToDead(file, result);
    }
}

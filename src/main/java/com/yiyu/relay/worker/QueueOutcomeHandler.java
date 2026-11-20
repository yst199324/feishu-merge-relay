package com.yiyu.relay.worker;

import com.yiyu.relay.client.ForwardResult;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.RetryLadder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 转发结论 → 队列动作 的唯一实现处。
 *
 * <p>PendingWorker 和 RetryWorker 的区别只在"失败后算第几次重试"，
 * 而"成功删文件""进死信""搬进重试并改时间"这三个动作完全一样，
 * 所以集中在这里。好处是死信原因的判定（auth / bad / exhausted / expired）
 * 只有一处，不会两个 worker 写得不一样。</p>
 */
@Component
public class QueueOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(QueueOutcomeHandler.class);

    private static final String CREDENTIAL_HINT =
            "云效凭据失效，请更换 x-yunxiao-token 后按原因 auth 手工补发";

    private final QueueStore queueStore;
    private final RetryLadder retryLadder;

    public QueueOutcomeHandler(QueueStore queueStore, RetryLadder retryLadder) {
        this.queueStore = queueStore;
        this.retryLadder = retryLadder;
    }

    /**
     * 转发成功：删文件。
     *
     * <p>删失败只记日志 —— 文件还在原地，下一轮会再转发一次，合并项目按投递编号
     * 去重，通常直接返回 200，不会重复发卡（设计文档 5.8 / 14.12）。</p>
     *
     * @param file 已成功的文件
     */
    public void deleteAfterSuccess(QueueFile file) {
        try {
            queueStore.delete(file);
        } catch (IOException e) {
            log.error("转发成功但删文件失败，下轮会重发一次（靠投递编号去重）：{}（{}）",
                    file.fileName(), e.getMessage());
        }
    }

    /**
     * 按失败结论进死信，原因标记自动判定。
     *
     * @param file   文件
     * @param result 转发结论
     */
    public void moveToDead(QueueFile file, ForwardResult result) {
        String reason = result.isCredentialFailure() ? QueueStore.REASON_AUTH : QueueStore.REASON_BAD;
        if (result.isCredentialFailure()) {
            log.error("{}（编号 {}）", CREDENTIAL_HINT, file.getDeliveryId());
        }
        moveToDead(file, reason, result.describe());
    }

    /**
     * 明确指定原因进死信（重试阶梯用尽时用）。
     *
     * @param file   文件
     * @param reason 原因标记
     * @param note   进日志的补充说明
     */
    public void moveToDead(QueueFile file, String reason, String note) {
        try {
            QueueFile dead = queueStore.moveToDead(file, reason);
            log.error("消息进入死信：{} → {}（编号 {}，原因 {}，{}）",
                    file.fileName(), dead.fileName(), file.getDeliveryId(), reason, note);
        } catch (IOException e) {
            log.error("消息进入死信失败，下轮再试：{}（{}）", file.fileName(), e.getMessage());
        }
    }

    /**
     * 搬进重试目录，并按阶梯把修改时间改成下次该重试的时刻。
     *
     * @param file     文件
     * @param attempts 新的已试次数，从 1 开始；梯队长度的含义见 {@link RetryLadder}
     */
    public void scheduleRetry(QueueFile file, int attempts) {
        long delayMillis = retryLadder.delayMillis(attempts);
        long nextRetryAtMillis = System.currentTimeMillis() + delayMillis;
        try {
            QueueFile retry = queueStore.moveToRetry(file, attempts, nextRetryAtMillis);
            log.warn("转发失败，进入第 {} 次重试（{} 秒后）：{}（编号 {}）",
                    attempts, delayMillis / 1000L, retry.fileName(), file.getDeliveryId());
        } catch (IOException e) {
            log.error("移入重试目录失败，文件仍在原目录、下轮会再转发：{}（{}）",
                    file.fileName(), e.getMessage());
        }
    }
}

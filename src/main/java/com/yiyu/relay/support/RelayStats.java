package com.yiyu.relay.support;

import com.yiyu.relay.client.ForwardResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 转发结果统计，供 /health 展示（设计文档 14.8 第三层）。
 *
 * <p>为什么放在内存里而不是落盘：这些数字只用于回答"现在到底通不通"。
 * 队列本身的事实（条数、最老文件）永远在磁盘上，进程重启也不会丢；
 * 统计丢了只是少一行参考信息，不值得再维护一份持久化状态。</p>
 *
 * <p>转发可能是多线程（{@code relay.target.threads > 1}），所以全部用原子类型。</p>
 */
@Component
public class RelayStats {

    private static final String NEVER_FORWARDED = "尚未转发过";

    private final AtomicReference<String> lastResult = new AtomicReference<String>(NEVER_FORWARDED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastSuccessAtMillis = new AtomicLong(0L);
    private final AtomicLong lastFailureAtMillis = new AtomicLong(0L);
    private final AtomicLong successCount = new AtomicLong(0L);
    private final AtomicLong failureCount = new AtomicLong(0L);

    /**
     * 记录一次转发结果。
     *
     * @param result 转发结果
     */
    public void record(ForwardResult result) {
        lastResult.set(result.describe());
        if (result.getOutcome().isSuccess()) {
            successCount.incrementAndGet();
            consecutiveFailures.set(0);
            lastSuccessAtMillis.set(System.currentTimeMillis());
        } else {
            failureCount.incrementAndGet();
            consecutiveFailures.incrementAndGet();
            lastFailureAtMillis.set(System.currentTimeMillis());
        }
    }

    /**
     * 取一份快照，供 /health 输出。
     *
     * @return 快照
     */
    public RelayStatsSnapshot snapshot() {
        return new RelayStatsSnapshot(
                lastResult.get(),
                consecutiveFailures.get(),
                lastSuccessAtMillis.get(),
                lastFailureAtMillis.get(),
                successCount.get(),
                failureCount.get());
    }
}

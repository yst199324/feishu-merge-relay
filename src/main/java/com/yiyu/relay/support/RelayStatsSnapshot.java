package com.yiyu.relay.support;

/**
 * /health 里"最近转发情况"的只读快照。
 *
 * <p>单独一个值对象而不是直接暴露 {@link RelayStats}，是为了让序列化结果稳定，
 * 也让调用方（HealthController）拿到的是一致的一份数据。</p>
 */
public final class RelayStatsSnapshot {

    private final String lastResult;
    private final int consecutiveFailures;
    private final long lastSuccessAtMillis;
    private final long lastFailureAtMillis;
    private final long successCount;
    private final long failureCount;

    public RelayStatsSnapshot(String lastResult,
                              int consecutiveFailures,
                              long lastSuccessAtMillis,
                              long lastFailureAtMillis,
                              long successCount,
                              long failureCount) {
        this.lastResult = lastResult;
        this.consecutiveFailures = consecutiveFailures;
        this.lastSuccessAtMillis = lastSuccessAtMillis;
        this.lastFailureAtMillis = lastFailureAtMillis;
        this.successCount = successCount;
        this.failureCount = failureCount;
    }

    /** 最近一次转发的结论描述，如"成功(200)"。启动后还没转发过时是一句提示。 */
    public String getLastResult() {
        return lastResult;
    }

    /** 连续失败次数。≥3 就该去看日志（对应手册第九章）。 */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** 最后一次成功转发的时间，0 表示从未成功过。 */
    public long getLastSuccessAtMillis() {
        return lastSuccessAtMillis;
    }

    /** 最后一次失败转发的时间，0 表示从未失败过。 */
    public long getLastFailureAtMillis() {
        return lastFailureAtMillis;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public long getFailureCount() {
        return failureCount;
    }
}

package com.yiyu.relay.queue;

/**
 * 三个队列的条数快照，启动日志和 /health 都用它。
 */
public final class QueueCounts {

    private final int pending;
    private final int retry;
    private final int dead;

    public QueueCounts(int pending, int retry, int dead) {
        this.pending = pending;
        this.retry = retry;
        this.dead = dead;
    }

    public int getPending() {
        return pending;
    }

    public int getRetry() {
        return retry;
    }

    public int getDead() {
        return dead;
    }

    /** 启动日志用的固定格式：{@code pending=0 retry=0 dead=0}。 */
    @Override
    public String toString() {
        return "pending=" + pending + " retry=" + retry + " dead=" + dead;
    }
}

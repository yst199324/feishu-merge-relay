package com.yiyu.relay.support;

import com.yiyu.relay.client.ForwardResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /health 展示的统计：只有真正成功才算成功，可重试失败要计入连续失败次数。
 *
 * <p>这条测试锁住一个真实踩过的坑：把"可重试失败"当成成功记进统计，
 * 结果 /health 里连续失败次数永远是 0，运维看不出下游一直不通。</p>
 */
class RelayStatsTest {

    @Test
    @DisplayName("连续失败计数只在成功时归零")
    void consecutiveFailuresCounted() {
        RelayStats stats = new RelayStats();
        stats.record(ForwardResult.transportFailure("连接失败"));
        stats.record(ForwardResult.retryable(503, "下游 503"));
        assertEquals(2, stats.snapshot().getConsecutiveFailures());
        assertEquals(0L, stats.snapshot().getSuccessCount());
        assertEquals(2L, stats.snapshot().getFailureCount());

        stats.record(ForwardResult.success(200));
        RelayStatsSnapshot snapshot = stats.snapshot();
        assertEquals(0, snapshot.getConsecutiveFailures());
        assertEquals(1L, snapshot.getSuccessCount());
        assertTrue(snapshot.getLastSuccessAtMillis() > 0L);
        assertTrue(snapshot.getLastResult().contains("成功"));
    }

    @Test
    @DisplayName("永久失败与内部入口未开也算失败")
    void permanentFailuresCounted() {
        RelayStats stats = new RelayStats();
        stats.record(ForwardResult.permanent(400, "报文解析失败", false));
        stats.record(ForwardResult.endpointMissing(404));
        assertEquals(2, stats.snapshot().getConsecutiveFailures());
        assertTrue(stats.snapshot().getLastResult().contains("内部入口未开"));
    }
}

package com.yiyu.relay.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认值守卫：这些数字是被实际链路耗时反推出来的，改小了会静默失效，所以用测试钉住。
 *
 * <p>尤其是读超时 —— 它从文档最初的 30 秒调到 120 秒，是因为合并项目同步链路
 * 最坏约 85 秒（最多 3 次云效调用，每次"连接 5s + 读 20s"，再加一次发飞书卡片）。
 * 若哪天有人把它改回 30 秒，这条测试会立刻失败。</p>
 */
class RelayPropertiesTest {

    /** 合并项目给的最坏耗时实测值（毫秒）。 */
    private static final long WORST_CASE_TARGET_MILLIS = 85_000L;

    @Test
    @DisplayName("读超时必须覆盖合并项目最坏 85 秒并留余量")
    void readTimeoutCoversWorstCaseTarget() {
        RelayProperties properties = new RelayProperties();
        int readTimeoutMs = properties.getTarget().getReadTimeoutMs();
        assertEquals(120_000, readTimeoutMs);
        assertTrue(readTimeoutMs > WORST_CASE_TARGET_MILLIS,
                "读超时小于下游最坏耗时会导致『其实成功了、我们判成失败』");
    }

    @Test
    @DisplayName("连接超时保持 3 秒，不影响最坏耗时结论")
    void connectTimeoutUnchanged() {
        assertEquals(3000, new RelayProperties().getTarget().getConnectTimeoutMs());
    }

    @Test
    @DisplayName("总寿命 3 小时，必须大于重试阶梯累计 2 小时 38 分")
    void maxAgeExceedsRetryLadderTotal() {
        RelayProperties properties = new RelayProperties();
        assertEquals(180L, properties.getMaxAgeMinutes());
        assertEquals(180L * 60L * 1000L, properties.maxAgeMillis());

        long ladderTotalSeconds = 0L;
        for (Long delay : properties.getRetry().getDelaySeconds()) {
            ladderTotalSeconds += delay.longValue();
        }
        assertTrue(properties.maxAgeMillis() / 1000L > ladderTotalSeconds);
    }

    @Test
    @DisplayName("让路阈值必须小于待发上限，否则规则永远不触发")
    void yieldThresholdIsBelowPendingMaxSize() {
        RelayProperties properties = new RelayProperties();
        assertTrue(properties.getRetry().getMaxPendingBeforeYield()
                < properties.getPending().getMaxSize());
    }
}

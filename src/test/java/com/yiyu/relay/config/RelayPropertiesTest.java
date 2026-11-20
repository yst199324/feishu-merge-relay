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

    /** 合并项目内部入口「占坑」记录的有效期（毫秒）。超时后同一条投递会被当成全新投递重做。 */
    private static final long TARGET_CLAIM_TTL_MILLIS = 30L * 60L * 1000L;

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

    /**
     * 阶梯里必须有一档落在合并项目"占坑 TTL"之后。
     *
     * <p>合并项目处理一条投递前先占坑，坑的有效期 30 分钟，起点是"第一次 claim 的时间"，
     * 而且重复访问（后续那些 IN_FLIGHT）<b>不刷新</b>它。所以万一它真卡死（拿了坑永不返回），
     * 只有等我方某一档重试落在"第一次进门 + 30 分钟"之后，那次重推才会被当成全新投递真正重做一遍。
     * 默认阶梯的第 5 档累计 37 分 35 秒正好跨过去。</p>
     *
     * <p><b>判据是"某一档累计等待 ≥ 30 分钟"，不能改成"总寿命 ≥ 30 分钟"</b>：
     * 总寿命 3 小时当然大于 30 分钟，但那只说明文件还在队列里，不代表中间有任何一次重推落在 TTL 之后。
     * 改小了阶梯（比如只留前 4 档，累计 7 分 35 秒）就会出现"文件能活 3 小时、却一次也没跨过 TTL"的情形 ——
     * 卡死的投递领完整条阶梯直接进死信，中间没有任何一次真正重做。这种失效不报错、不告警，
     * 现象只是"重试都失败了"，极难归因到阶梯长度上。</p>
     */
    @Test
    @DisplayName("阶梯必须有一档跨过合并项目 30 分钟占坑 TTL，且仍早于总寿命")
    void retryLadderCrossesTargetClaimTtl() {
        RelayProperties properties = new RelayProperties();
        long claimTtlSeconds = TARGET_CLAIM_TTL_MILLIS / 1000L;
        long maxAgeSeconds = properties.maxAgeMillis() / 1000L;

        long cumulativeSeconds = 0L;
        boolean crossesClaimTtl = false;
        for (Long delay : properties.getRetry().getDelaySeconds()) {
            cumulativeSeconds += delay.longValue();
            if (cumulativeSeconds >= claimTtlSeconds && cumulativeSeconds <= maxAgeSeconds) {
                crossesClaimTtl = true;
            }
        }
        assertTrue(crossesClaimTtl,
                "没有一档落在 30 分钟占坑 TTL 之后：合并项目真卡死时，这条消息只会一路处理中走完阶梯进死信");
    }
}

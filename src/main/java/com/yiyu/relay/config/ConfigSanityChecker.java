package com.yiyu.relay.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时检查配置项之间那些"互相约束"的取值。
 *
 * <p>这些约束不是随便定的，破了任意一条，功能会静默失效而不是报错：</p>
 * <ul>
 *   <li>总寿命必须大于重试阶梯累计时长，否则最后一档（2 小时）永远跑不到就被判超龄；</li>
 *   <li>让路阈值必须小于待发队列上限，否则"积压超过阈值就让路"永远不触发；</li>
 *   <li>Webhook 密钥为空/仍是占位值，所有推送都会被 403 —— 看起来像网关问题。</li>
 * </ul>
 *
 * <p>只警告不退出：生产上宁可服务照常跑、日志里写清楚，也不要因为一个可以热改的
 * 参数把服务拦住（除了队列目录建不出来那种致命情况）。</p>
 */
@Component
public class ConfigSanityChecker {

    private static final Logger log = LoggerFactory.getLogger(ConfigSanityChecker.class);

    /** 读超时低于这个值就该警告：合并项目最坏链路约 85 秒，30 秒只是"能跑但会误判"的下限。 */
    private static final int MIN_SAFE_READ_TIMEOUT_MS = 30000;

    /** 读超时高于这个值就没意义了：待发队列串行，一条卡住会拖住整轮，积压恢复更慢。 */
    private static final int MAX_REASONABLE_READ_TIMEOUT_MS = 180000;

    private final RelayProperties properties;

    public ConfigSanityChecker(RelayProperties properties) {
        this.properties = properties;
    }

    /** 跑一遍检查，问题全部以 WARN / ERROR 记日志。 */
    public void checkAndLog() {
        checkWebhookToken();
        checkMaxAgeAgainstRetryLadder();
        checkYieldThreshold();
        checkTargetConfiguration();
    }

    private void checkWebhookToken() {
        String token = properties.getWebhookToken();
        if (token == null || token.trim().isEmpty()) {
            log.error("relay.webhook-token 为空：所有云效推送都会被 403 拒绝。请检查 .env 里的 YUNXIAO_WEBHOOK_TOKEN");
            return;
        }
        if (token.contains(RelayProperties.PLACEHOLDER_MARK)) {
            log.error("relay.webhook-token 仍是占位值：所有云效推送都会被 403 拒绝。请检查 .env 里的 YUNXIAO_WEBHOOK_TOKEN");
        }
    }

    private void checkMaxAgeAgainstRetryLadder() {
        long ladderTotalSeconds = totalDelaySeconds();
        long maxAgeSeconds = properties.maxAgeMillis() / 1000L;
        if (maxAgeSeconds <= ladderTotalSeconds) {
            log.warn("relay.max-age-minutes（{} 秒）不大于重试阶梯累计（{} 秒）：最后一档重试会来不及跑，"
                            + "消息会被提前判超龄进死信。建议至少 {} 分钟",
                    maxAgeSeconds, ladderTotalSeconds, (ladderTotalSeconds / 60L) + 1L);
        }
    }

    private void checkYieldThreshold() {
        int threshold = properties.getRetry().getMaxPendingBeforeYield();
        int pendingMax = properties.getPending().getMaxSize();
        if (threshold >= pendingMax) {
            log.warn("relay.retry.max-pending-before-yield（{}）不小于 relay.pending.max-size（{}）："
                    + "待发队列永远不可能积压到阈值，让路规则形同虚设", threshold, pendingMax);
        }
    }

    private void checkTargetConfiguration() {
        String url = properties.getTarget().getUrl();
        if (url == null || url.trim().isEmpty()) {
            log.error("relay.target.url 为空：所有转发都会失败，请填合并项目的内部入口地址");
            return;
        }
        int readTimeoutMs = properties.getTarget().getReadTimeoutMs();
        if (readTimeoutMs < MIN_SAFE_READ_TIMEOUT_MS) {
            log.warn("relay.target.read-timeout-ms（{} ms）小于建议值 {} ms：合并项目同步链路"
                            + "最坏约 85 秒（最多 3 次云效调用 + 一次发卡），设太短会出现"
                            + "『它其实成功了、我们判成失败』，白占一次重试名额",
                    readTimeoutMs, MIN_SAFE_READ_TIMEOUT_MS);
        }
        if (readTimeoutMs > MAX_REASONABLE_READ_TIMEOUT_MS) {
            log.warn("relay.target.read-timeout-ms（{} ms）偏大：待发队列是串行处理的，"
                            + "一条消息卡住会把整轮拖住这么久，积压恢复更慢", readTimeoutMs);
        }
    }

    private long totalDelaySeconds() {
        List<Long> delays = properties.getRetry().getDelaySeconds();
        if (delays == null || delays.isEmpty()) {
            // RetryLadder 在配置被清空时会回落默认阶梯，这里做同样的兜底，口径才一致
            delays = RelayProperties.DEFAULT_DELAY_SECONDS;
        }
        long total = 0L;
        for (Long delay : delays) {
            if (delay != null) {
                total += delay.longValue();
            }
        }
        return total;
    }
}

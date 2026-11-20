package com.yiyu.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 监听服务的全部配置项，与 application.yml 的 {@code relay.*} 一一对应
 * （对应设计文档 8.1 与 .env.example）。
 *
 * <p>为什么用可变的 getter/setter 而不是构造器绑定：Spring Boot 2.7 的
 * {@code @ConfigurationProperties} 构造器绑定需要 {@code @ConstructorBinding}，
 * 嵌套类多、默认值多，写起来容易出错；这里的对象只在启动时绑定一次，没有并发写。</p>
 */
@ConfigurationProperties(prefix = "relay")
public class RelayProperties {

    /** 默认重试阶梯：5 秒 → 30 秒 → 2 分 → 5 分 → 30 分 → 2 小时。 */
    public static final List<Long> DEFAULT_DELAY_SECONDS =
            Arrays.asList(5L, 30L, 120L, 300L, 1800L, 7200L);

    /** 云效 Webhook 密钥。占位值只用于"没配也要能起来"的本地开发。 */
    private String webhookToken = "在这里填云效后台的密钥";

    /** 报文最大字节数，超过直接 413，不落盘。默认 2 MB。 */
    private long maxPayloadBytes = 2097152L;

    /** 队列根目录，下面自动建 pending / retry / dead 三个子目录。 */
    private String queueDir = "./queue";

    /** 扫描间隔（毫秒）。 */
    private long scanIntervalMs = 2000L;

    /** 消息总寿命（分钟），从首次到达算，pending 与 retry 共用。 */
    private long maxAgeMinutes = 180L;

    private Pending pending = new Pending();
    private Retry retry = new Retry();
    private Dead dead = new Dead();
    private Target target = new Target();

    public String getWebhookToken() {
        return webhookToken;
    }

    public void setWebhookToken(String webhookToken) {
        this.webhookToken = webhookToken;
    }

    public long getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(long maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public String getQueueDir() {
        return queueDir;
    }

    public void setQueueDir(String queueDir) {
        this.queueDir = queueDir;
    }

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public long getMaxAgeMinutes() {
        return maxAgeMinutes;
    }

    public void setMaxAgeMinutes(long maxAgeMinutes) {
        this.maxAgeMinutes = maxAgeMinutes;
    }

    public Pending getPending() {
        return pending;
    }

    public void setPending(Pending pending) {
        this.pending = pending;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Dead getDead() {
        return dead;
    }

    public void setDead(Dead dead) {
        this.dead = dead;
    }

    public Target getTarget() {
        return target;
    }

    public void setTarget(Target target) {
        this.target = target;
    }

    /** 消息总寿命（毫秒）。 */
    public long maxAgeMillis() {
        return maxAgeMinutes * 60_000L;
    }

    /** 死信保留期（毫秒）。 */
    public long deadRetentionMillis() {
        return dead.getMaxAgeDays() * 24L * 60L * 60L * 1000L;
    }

    /** 待发队列配置。 */
    public static class Pending {
        private int maxSize = 50;
        private int batchPerRound = 10;

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getBatchPerRound() {
            return batchPerRound;
        }

        public void setBatchPerRound(int batchPerRound) {
            this.batchPerRound = batchPerRound;
        }
    }

    /** 重试队列配置。 */
    public static class Retry {
        private int maxSize = 50;
        private int batchPerRound = 5;
        private int maxPendingBeforeYield = 10;

        /**
         * 每个条目是一次重试前的等待秒数，条目数就是重试次数。
         * 用 Long 列表而不是数组：yaml 里的 [5, 30, ...] 绑成 List 更自然。
         */
        private List<Long> delaySeconds = new ArrayList<Long>(DEFAULT_DELAY_SECONDS);

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getBatchPerRound() {
            return batchPerRound;
        }

        public void setBatchPerRound(int batchPerRound) {
            this.batchPerRound = batchPerRound;
        }

        public int getMaxPendingBeforeYield() {
            return maxPendingBeforeYield;
        }

        public void setMaxPendingBeforeYield(int maxPendingBeforeYield) {
            this.maxPendingBeforeYield = maxPendingBeforeYield;
        }

        public List<Long> getDelaySeconds() {
            return delaySeconds;
        }

        public void setDelaySeconds(List<Long> delaySeconds) {
            this.delaySeconds = delaySeconds;
        }
    }

    /** 死信目录配置。 */
    public static class Dead {
        private int maxSize = 500;
        private long maxAgeDays = 7L;

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public long getMaxAgeDays() {
            return maxAgeDays;
        }

        public void setMaxAgeDays(long maxAgeDays) {
            this.maxAgeDays = maxAgeDays;
        }
    }

    /** 转发目标（合并项目内部入口）配置。 */
    public static class Target {
        private String url = "http://127.0.0.1:18601/internal/yunxiao";
        private int connectTimeoutMs = 3000;

        /**
         * 等响应超时，默认 120 秒。
         *
         * <p>不是 30 秒：合并项目同步处理一条要串行调最多 3 次云效接口（每次"连接 5s + 读 20s"上限）
         * 再发一次飞书卡片，最坏约 85 秒。设短了会出现"它其实成功了、我们判成失败"→ 重投 →
         * 靠投递编号去重挡住，但白白浪费一次重试名额。调大只影响"判定失败"的早晚，不影响正确性。</p>
         */
        private int readTimeoutMs = 120000;
        private int threads = 1;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }
    }
}

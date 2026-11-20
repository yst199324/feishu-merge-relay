package com.yiyu.relay.web;

import com.yiyu.relay.queue.QueueCounts;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.RelayStats;
import com.yiyu.relay.support.RelayStatsSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读自检接口 {@code GET /health}（设计文档第九章、14.8 第三层）。
 *
 * <p>它回答四个问题：三个队列各多少条、最老的一条进来多久了、最近一次转发结果与
 * 连续失败次数、最后一次成功是什么时候。运维看一眼就知道是"队列空着一切正常"
 * 还是"一直调不通、文件在积压"。</p>
 *
 * <p>它挂在对外端口上、不受合并项目的 TEST_ENDPOINTS 开关影响，
 * 所以是生产上真正随时能看的那一个（风险 P14：无鉴权，只暴露队列条数）。</p>
 */
@RestController
public class HealthController {

    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 队列为空时，"最老一条多久"没有意义，用负数表示。 */
    private static final long NO_AGE = -1L;

    private final QueueStore queueStore;
    private final RelayStats relayStats;

    public HealthController(QueueStore queueStore, RelayStats relayStats) {
        this.queueStore = queueStore;
        this.relayStats = relayStats;
    }

    /**
     * 自检。
     *
     * @return 队列条数、最老文件年龄、最近转发情况
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        long now = System.currentTimeMillis();
        QueueCounts counts = queueStore.counts();
        RelayStatsSnapshot stats = relayStats.snapshot();

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("queueDir", queueStore.getQueueRoot().toString());
        body.put("pending", counts.getPending());
        body.put("retry", counts.getRetry());
        body.put("dead", counts.getDead());
        body.put("oldestPendingAgeSeconds", ageSeconds(now, queueStore.oldestPendingArrivedAtMillis()));
        body.put("oldestRetryAgeSeconds", ageSeconds(now, queueStore.oldestRetryArrivedAtMillis()));
        body.put("lastForwardResult", stats.getLastResult());
        body.put("consecutiveFailures", stats.getConsecutiveFailures());
        body.put("lastSuccessAt", formatTime(stats.getLastSuccessAtMillis()));
        body.put("lastFailureAt", formatTime(stats.getLastFailureAtMillis()));
        body.put("successCount", stats.getSuccessCount());
        body.put("failureCount", stats.getFailureCount());
        body.put("serverTime", formatTime(now));
        return body;
    }

    private long ageSeconds(long now, long arrivedAtMillis) {
        if (arrivedAtMillis <= 0L) {
            return NO_AGE;
        }
        return Math.max(0L, (now - arrivedAtMillis) / 1000L);
    }

    private String formatTime(long millis) {
        if (millis <= 0L) {
            return "从未";
        }
        return new SimpleDateFormat(TIME_PATTERN).format(new Date(millis));
    }
}

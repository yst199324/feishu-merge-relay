package com.yiyu.relay.support;

import com.yiyu.relay.config.RelayProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 重试阶梯：第 n 次重试之前要等多少秒。
 *
 * <p>阶梯就是一个配置列表（{@code relay.retry.delay-seconds}），<b>列表里有几个数就是重试几次</b>。
 * 想加一档就加一个数，不用改代码 —— 这是本节唯一需要"可扩展"的地方，所以单独拎成一个类。</p>
 *
 * <p>越界取最后一档：调用方（{@code RetryWorker}）用 {@link #maxAttempts()} 判断是否该进死信，
 * 这里的越界兜底只是防御性写法，避免配置被改小后抛下标越界。</p>
 */
@Component
public class RetryLadder {

    private final List<Long> delaySeconds;

    /**
     * Spring 注入用：从配置取阶梯。
     *
     * @param properties 配置
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RetryLadder(RelayProperties properties) {
        this(resolveDelaySeconds(properties));
    }

    /**
     * 测试用：直接给定阶梯。
     *
     * @param delaySeconds 每档等待秒数，至少一项
     */
    public RetryLadder(List<Long> delaySeconds) {
        List<Long> normalized = new ArrayList<Long>();
        if (delaySeconds != null) {
            for (Long value : delaySeconds) {
                if (value != null && value >= 0L) {
                    normalized.add(value);
                }
            }
        }
        if (normalized.isEmpty()) {
            // 配置被误清空时不至于"第一条失败就直接进死信"，回落到默认阶梯
            normalized.addAll(RelayProperties.DEFAULT_DELAY_SECONDS);
        }
        this.delaySeconds = Collections.unmodifiableList(normalized);
    }

    private static List<Long> resolveDelaySeconds(RelayProperties properties) {
        return properties.getRetry() == null ? null : properties.getRetry().getDelaySeconds();
    }

    /**
     * 第 {@code attemptNumber} 次重试前的等待毫秒数。
     *
     * @param attemptNumber 第几次重试，从 1 开始；小于 1 按第 1 次算
     * @return 等待毫秒数
     */
    public long delayMillis(int attemptNumber) {
        return delaySeconds.get(indexOf(attemptNumber)) * 1000L;
    }

    /**
     * 阶梯长度 = 最大重试次数。超过这个次数还没成功就应该进死信。
     *
     * @return 最大重试次数
     */
    public int maxAttempts() {
        return delaySeconds.size();
    }

    /** 把 1 基的重试序号转成 0 基下标，并做边界收敛。 */
    private int indexOf(int attemptNumber) {
        int index = attemptNumber - 1;
        if (index < 0) {
            return 0;
        }
        if (index >= delaySeconds.size()) {
            return delaySeconds.size() - 1;
        }
        return index;
    }
}

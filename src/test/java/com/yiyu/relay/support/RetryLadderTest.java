package com.yiyu.relay.support;

import com.yiyu.relay.config.RelayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 重试阶梯计算：第 n 次重试前等多少秒，越界取最后一档。
 */
class RetryLadderTest {

    @Test
    @DisplayName("默认阶梯：5 秒 → 30 秒 → 2 分 → 5 分 → 30 分 → 2 小时")
    void defaultLadder() {
        RetryLadder ladder = new RetryLadder(new RelayProperties());
        assertEquals(6, ladder.maxAttempts());
        assertEquals(5_000L, ladder.delayMillis(1));
        assertEquals(30_000L, ladder.delayMillis(2));
        assertEquals(120_000L, ladder.delayMillis(3));
        assertEquals(300_000L, ladder.delayMillis(4));
        assertEquals(1_800_000L, ladder.delayMillis(5));
        assertEquals(7_200_000L, ladder.delayMillis(6));
    }

    @Test
    @DisplayName("越界取最后一档，第 0 次按第一档")
    void outOfRangeFallsBackToLastStep() {
        RetryLadder ladder = new RetryLadder(Arrays.asList(5L, 30L));
        assertEquals(2, ladder.maxAttempts());
        assertEquals(5_000L, ladder.delayMillis(0));
        assertEquals(5_000L, ladder.delayMillis(1));
        assertEquals(30_000L, ladder.delayMillis(2));
        assertEquals(30_000L, ladder.delayMillis(99), "越界应取最后一档，而不是抛下标越界");
    }

    @Test
    @DisplayName("阶梯条目数就是重试次数：改配置即可增减档位")
    void ladderLengthIsRetryCount() {
        assertEquals(3, new RetryLadder(Arrays.asList(5L, 5L, 5L)).maxAttempts());
        assertEquals(1, new RetryLadder(Collections.singletonList(1L)).maxAttempts());
    }

    @Test
    @DisplayName("配置被清空时回落默认阶梯，不会出现『第一条失败就进死信』")
    void emptyConfigurationFallsBackToDefault() {
        RetryLadder ladder = new RetryLadder(Collections.<Long>emptyList());
        assertEquals(RelayProperties.DEFAULT_DELAY_SECONDS.size(), ladder.maxAttempts());
    }

    @Test
    @DisplayName("累计时长 2 小时 38 分，必须小于总寿命 3 小时")
    void totalDelayIsShorterThanDefaultMaxAge() {
        RetryLadder ladder = new RetryLadder(new RelayProperties());
        long totalMillis = 0L;
        for (int attempt = 1; attempt <= ladder.maxAttempts(); attempt++) {
            totalMillis += ladder.delayMillis(attempt);
        }
        assertEquals(9_455_000L, totalMillis);
        assertEquals(10_800_000L, new RelayProperties().maxAgeMillis());
    }
}

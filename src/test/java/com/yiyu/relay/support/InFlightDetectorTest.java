package com.yiyu.relay.support;

import com.yiyu.relay.client.ForwardResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "处理中（IN_FLIGHT）"识别：只影响日志措辞，不影响归类。
 */
class InFlightDetectorTest {

    @Test
    @DisplayName("503 + outcome=IN_FLIGHT → 按正常节流打日志")
    void inFlightRetryableIsRecognised() {
        ForwardResult result = ForwardResult.retryable(503,
                "{\"outcome\":\"IN_FLIGHT\",\"note\":\"处理中：这条投递还在处理，本次没有重复执行 delivery=abc\"}");
        assertTrue(InFlightDetector.isInFlight(result));
    }

    @Test
    @DisplayName("普通 503（云效挂了 / 超时）不算处理中")
    void plainRetryableIsNotInFlight() {
        assertFalse(InFlightDetector.isInFlight(ForwardResult.retryable(503, "云效接口失败 HTTP 502")));
        assertFalse(InFlightDetector.isInFlight(ForwardResult.transportFailure("连接失败: refused")));
    }

    @Test
    @DisplayName("成功与永久失败一律不算处理中")
    void otherOutcomesAreNeverInFlight() {
        assertFalse(InFlightDetector.isInFlight(ForwardResult.success(200, "{\"outcome\":\"DONE\"}")));
        assertFalse(InFlightDetector.isInFlight(ForwardResult.permanent(400, "报文解析失败", false)));
        assertFalse(InFlightDetector.isInFlight(ForwardResult.endpointMissing(404)));
        assertFalse(InFlightDetector.isInFlight(null));
    }

    @Test
    @DisplayName("归类不受影响：识别成功仍然是可重试（进 retry 队列、不删文件）")
    void classificationUnchanged() {
        ForwardResult result = ForwardResult.retryable(503, "{\"outcome\":\"IN_FLIGHT\"}");
        assertTrue(InFlightDetector.isInFlight(result));
        assertFalse(result.getOutcome().isSuccess());
        assertTrue(result.getOutcome().isRetryable());
    }
}

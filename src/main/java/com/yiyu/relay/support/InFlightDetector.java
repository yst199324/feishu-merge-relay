package com.yiyu.relay.support;

import com.yiyu.relay.client.ForwardOutcome;
import com.yiyu.relay.client.ForwardResult;

import java.util.Locale;

/**
 * 识别"合并项目正在处理同一条投递"这种 503（响应体 {@code outcome=IN_FLIGHT}）。
 *
 * <p>为什么值得单独识别：它对监听服务的处理动作和普通 503 完全一样（都进重试队列），
 * 但排查方向正好相反 —— 普通 503 是"下游坏了"，IN_FLIGHT 是"下游正在干活，让我等"。
 * 两者混成一句"转发失败"，运维看到成片 WARN 会去查网络，实际只是积压回放时的正常节流。</p>
 *
 * <p>只用于日志措辞，<b>不改变归类</b>：仍然是 RETRYABLE，仍然按阶梯重试、仍然不删文件。</p>
 */
public final class InFlightDetector {

    /** 合并项目响应体里表示"处理中"的结论标记。 */
    private static final String IN_FLIGHT_MARKER = "IN_FLIGHT";

    private InFlightDetector() {
        // 工具类，不实例化
    }

    /**
     * 这次失败是不是"下游还在处理中"。
     *
     * @param result 转发结论
     * @return true 表示可以按"正常节流"打日志
     */
    public static boolean isInFlight(ForwardResult result) {
        if (result == null || result.getOutcome() != ForwardOutcome.RETRYABLE) {
            return false;
        }
        String detail = result.getDetail();
        return detail != null && detail.toUpperCase(Locale.ROOT).contains(IN_FLIGHT_MARKER);
    }
}

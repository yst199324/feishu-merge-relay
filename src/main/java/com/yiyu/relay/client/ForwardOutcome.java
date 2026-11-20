package com.yiyu.relay.client;

/**
 * 一次转发的四种归宿。设计文档 6.2 要求"用白名单归类，不要用异常类别去猜"，
 * 所以把结论收敛成这四种枚举值，worker 只按这个枚举决定去留。
 */
public enum ForwardOutcome {

    /** 200 / 201 / 204：合并项目已收下（含"重复投递"和"非合并请求事件，已忽略"）。 */
    SUCCESS(true, "成功"),

    /** 400 / 413：重试再多次也没用，直接进死信（含云效凭据失效，原因记 auth）。 */
    PERMANENT(false, "永久失败"),

    /**
     * 404 / 405：合并项目内部入口没开或路径写错（开关 INTERNAL_ENDPOINT 默认 false）。
     * 这种失败必须单独归类 —— 若按"其余都重试"处理，每条消息都白等六次、跨 2 小时 38 分，
     * 日志里只写"转发失败"，看不出根因（设计文档 6.2、14.8）。
     */
    ENDPOINT_MISSING(false, "内部入口未开"),

    /** 5xx、连接失败、读超时：可重试。注意它<b>不算成功</b>，/health 里要计入连续失败次数。 */
    RETRYABLE(false, "可重试失败");

    private final boolean success;
    private final String label;

    ForwardOutcome(boolean success, String label) {
        this.success = success;
        this.label = label;
    }

    /** 是否算转发成功（成功后删文件）。 */
    public boolean isSuccess() {
        return success;
    }

    /** 是否值得再试一次（只用于日志描述，真正的调度在 worker 里）。 */
    public boolean isRetryable() {
        return this == RETRYABLE;
    }

    /** 中文标签，日志和 /health 里用。 */
    public String getLabel() {
        return label;
    }
}

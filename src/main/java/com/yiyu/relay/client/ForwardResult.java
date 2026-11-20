package com.yiyu.relay.client;

/**
 * 一次转发的完整结论：归类 + 原始状态码 + 给人看的描述。
 *
 * <p>不可变值对象，用静态工厂构造，避免出现"归类和状态码互相矛盾"的实例。</p>
 */
public final class ForwardResult {

    /** 没拿到 HTTP 响应（连接被拒、读超时）时用的状态码。 */
    public static final int NO_STATUS = -1;

    /**
     * 描述摘要的最大长度。
     *
     * <p>取 300 而不是 200：合并项目的响应体是 {@code {"outcome":...,"note":...}}，
     * 它最长的 note 约 150 字符，加上 JSON 外壳后 200 容易被截掉后半句，
     * 而"截掉的那半句"往往正是结论（DONE/IGNORED + 具体原因）。</p>
     */
    private static final int DETAIL_MAX_LENGTH = 300;

    private final ForwardOutcome outcome;
    private final int httpStatus;
    private final String detail;
    private final boolean credentialFailure;

    private ForwardResult(ForwardOutcome outcome, int httpStatus, String detail, boolean credentialFailure) {
        this.outcome = outcome;
        this.httpStatus = httpStatus;
        this.detail = detail == null ? "" : truncate(detail);
        this.credentialFailure = credentialFailure;
    }

    /** 成功收尾。 */
    public static ForwardResult success(int httpStatus) {
        return success(httpStatus, "");
    }

    /**
     * 成功收尾，并带上响应体摘要。
     *
     * <p>为什么要留响应体：合并项目用同一套返回码表达不同语义
     * （"已发卡片" 与 "跳过：这条投递已经发过卡片了" 都是 200，靠响应体里的 outcome 区分）。
     * 留一份摘要，出问题时能把日志和它那边的处理记录对上。</p>
     *
     * @param httpStatus 状态码
     * @param detail     响应体摘要（转发层会截断）
     */
    public static ForwardResult success(int httpStatus, String detail) {
        return new ForwardResult(ForwardOutcome.SUCCESS, httpStatus, detail, false);
    }

    /** 永久失败（400 / 413）。{@code credentialFailure} 为 true 时死信原因记 auth。 */
    public static ForwardResult permanent(int httpStatus, String detail, boolean credentialFailure) {
        return new ForwardResult(ForwardOutcome.PERMANENT, httpStatus, detail, credentialFailure);
    }

    /** 内部入口没开（404 / 405）。 */
    public static ForwardResult endpointMissing(int httpStatus) {
        return new ForwardResult(ForwardOutcome.ENDPOINT_MISSING, httpStatus, "内部入口返回 " + httpStatus, false);
    }

    /** 可重试失败：5xx、其它非白名单状态码。 */
    public static ForwardResult retryable(int httpStatus, String detail) {
        return new ForwardResult(ForwardOutcome.RETRYABLE, httpStatus, detail, false);
    }

    /** 连不上、读超时、本地读文件失败等没有状态码的情况，一律可重试。 */
    public static ForwardResult transportFailure(String detail) {
        return new ForwardResult(ForwardOutcome.RETRYABLE, NO_STATUS, detail, false);
    }

    public ForwardOutcome getOutcome() {
        return outcome;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isCredentialFailure() {
        return credentialFailure;
    }

    /**
     * 一行描述，写日志和 /health 都用它，保证两处口径一致。
     *
     * @return 如"成功(200)"、"永久失败(400: 报文解析失败)"
     */
    public String describe() {
        String statusText = httpStatus == NO_STATUS ? "无响应" : String.valueOf(httpStatus);
        return detail.isEmpty()
                ? outcome.getLabel() + "(" + statusText + ")"
                : outcome.getLabel() + "(" + statusText + ": " + detail + ")";
    }

    private static String truncate(String detail) {
        String singleLine = detail.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= DETAIL_MAX_LENGTH
                ? singleLine
                : singleLine.substring(0, DETAIL_MAX_LENGTH) + "...";
    }
}

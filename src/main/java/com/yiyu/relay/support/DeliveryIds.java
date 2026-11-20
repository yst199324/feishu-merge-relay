package com.yiyu.relay.support;

/**
 * 投递编号清洗（设计文档 5.2 / 14.3）。
 *
 * <p>编号来自请求头，是彻头彻尾的外部输入，会被直接拼进文件名。
 * 不清洗的话一个 {@code X-Codeup-Delivery: ../../etc/passwd} 就能往任意路径写文件。
 * 规则：只保留字母、数字、短横线、下划线，其余一律替换成短横线，长度截到 64 位。</p>
 */
public final class DeliveryIds {

    /** 文件名里编号的最大长度。云效实际编号是 36 位 UUID，64 已很宽松。 */
    public static final int MAX_LENGTH = 64;

    private static final char REPLACEMENT = '-';

    private DeliveryIds() {
        // 工具类，不实例化
    }

    /**
     * 清洗投递编号。
     *
     * @param raw 请求头里的原始编号，可为 null
     * @return 只含 {@code [A-Za-z0-9_-]} 的编号，长度不超过 {@link #MAX_LENGTH}；
     *         输入为 null 或空时返回空串
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(trimmed.length(), MAX_LENGTH));
        for (int i = 0; i < trimmed.length() && cleaned.length() < MAX_LENGTH; i++) {
            char c = trimmed.charAt(i);
            cleaned.append(isAllowed(c) ? c : REPLACEMENT);
        }
        return cleaned.toString();
    }

    /** 是否是需要报告"编号不合法"的空值（清洗后仍是空串）。 */
    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '_';
    }
}

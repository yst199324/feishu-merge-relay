package com.yiyu.relay.support;

import java.util.Locale;

/**
 * 敏感信息打码。
 *
 * <p>规则：**留前 4 个字符明文，后面每个字符换成一个 {@code *}**。</p>
 *
 * <p>为什么不全打星号：全打掉之后两份日志就长得一模一样，排查时分不清"两次推送是不是同一个
 * 密钥""我配的这个和云效那个到底差在哪"。留前 4 位既能比对是不是同一个值，
 * 又不足以还原出密钥（云效的密钥是长随机串，知道前 4 位没有任何用）。</p>
 *
 * <p>星号个数跟真实长度一致（最多 32 个），所以长度的差异也能一眼看出来 ——
 * 密钥末尾多粘了一个换行、少粘了一位，这类错最常见。</p>
 *
 * <p>注意这里的关键字表只用于**请求头名**。报文 JSON 里的键名是另一套规则
 * （{@link BodyDumper}），因为头名习惯写 {@code Authorization}、{@code X-XX-Signature}，
 * 而 JSON 里更常见的是 {@code access_token}、{@code sign}。两套不合并是故意的。</p>
 */
public final class Secrets {

    /** 名字里含这些词的请求头或 JSON 字段，值要打码。 */
    private static final String[] KEYWORDS = {
            "token", "secret", "authorization", "signature", "password", "cookie"
    };

    /** 保留明文的字符数。 */
    private static final int KEEP_PREFIX = 4;

    /** 星号最多这么多个，避免超长值刷屏。 */
    private static final int MAX_STARS = 32;

    private Secrets() {
        // 工具类，不实例化
    }

    /**
     * 判断一个名字（请求头名、JSON 字段名）是否涉及敏感信息。
     *
     * @param name 名字，可为 null
     * @return true 表示这个字段的值要打码
     */
    public static boolean isSensitiveName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String keyword : KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把值打成"前几位明文 + 星号"。
     *
     * @param value 原始值，可为 null
     * @return 打码后的值；空值返回空串
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int keep = Math.min(KEEP_PREFIX, value.length());
        // 至少一个星号：一字密钥也不能整字露出来
        int stars = Math.max(1, Math.min(value.length() - keep, MAX_STARS));
        StringBuilder masked = new StringBuilder(keep + stars);
        masked.append(value, 0, keep);
        for (int i = 0; i < stars; i++) {
            masked.append('*');
        }
        return masked.toString();
    }
}

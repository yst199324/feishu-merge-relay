package com.yiyu.relay.support;

import org.springframework.http.HttpHeaders;

import java.util.Locale;

/**
 * 请求头脱敏打印工具。
 *
 * <p>设计文档 6.1 要求"缺 X-Codeup-Delivery 时把请求头全量打出来"，但请求头里
 * 就躺着 {@code X-Codeup-Token} 本身。全量打印等于把密钥写进日志文件，
 * 所以这里按名字把它打码 —— 排查网关问题时只看"有没有这个头"就够了，不需要值。</p>
 */
public final class HeaderDumper {

    private static final String[] SENSITIVE_KEYWORDS = {"token", "secret", "authorization", "signature", "password"};
    private static final String MASK = "***(已脱敏)";
    private static final int MAX_VALUE_LENGTH = 200;

    private HeaderDumper() {
        // 工具类，不实例化
    }

    /**
     * 把请求头拼成一行，敏感的只留名字。
     *
     * @param headers 请求头
     * @return 形如 {@code {host=127.0.0.1, x-codeup-token=***(已脱敏)}} 的描述，不会为 null
     */
    public static String dump(HttpHeaders headers) {
        if (headers == null) {
            return "{}";
        }
        StringBuilder text = new StringBuilder("{");
        boolean first = true;
        for (String name : headers.keySet()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(name).append('=').append(valueOf(name, headers.getFirst(name)));
        }
        return text.append('}').toString();
    }

    private static String valueOf(String name, String value) {
        return isSensitive(name) ? MASK : truncate(value);
    }

    private static boolean isSensitive(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH) + "...";
    }
}

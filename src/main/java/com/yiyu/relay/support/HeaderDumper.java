package com.yiyu.relay.support;

import org.springframework.http.HttpHeaders;

/**
 * 把请求头拼成一行打印。
 *
 * <p>请求头里躺着 {@link HeaderNames#TOKEN} 本身，全量打印等于把密钥写进日志文件，
 * 所以按名字交给 {@link Secrets} 打码（留前 4 位明文，方便比对是不是同一个密钥）。</p>
 *
 * <p>非敏感的值只截到 {@link #MAX_VALUE_LENGTH} 个字符 —— 请求头本身不该有长内容，
 * 真有就是有人在往头里塞报文，截断避免把一行日志撑成几兆。</p>
 *
 * <p>设计文档 6.1：缺 {@code X-Codeup-Delivery} 时要能看到完整的头，
 * 排查网关是不是把未声明的头滤掉了。</p>
 */
public final class HeaderDumper {

    /** 单个非敏感头最多打印这么多个字符。 */
    private static final int MAX_VALUE_LENGTH = 200;

    private HeaderDumper() {
        // 工具类，不实例化
    }

    /**
     * 把请求头拼成一行。
     *
     * @param headers 请求头，可为 null
     * @return 形如 {@code {Host=127.0.0.1, X-Codeup-Token=9f1c********}} 的描述，不会为 null
     */
    public static String dump(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
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
        if (Secrets.isSensitiveName(name)) {
            return Secrets.mask(value);
        }
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_VALUE_LENGTH
                ? value
                : value.substring(0, MAX_VALUE_LENGTH) + "...";
    }
}

package com.yiyu.relay.support;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把报文正文压成一行日志。
 *
 * <p>三件事，顺序不能换：</p>
 * <ol>
 *   <li><b>先给 JSON 里的敏感字段打码</b>。先打码再截断，才不会出现"截断正好把一个密钥
 *       切成两半、后半截漏出去"。</li>
 *   <li><b>再压成一行</b>：把回车换行转义成 {@code \n} 的字面形式。云效报文是压过一行的
 *       JSON，但不保证 —— 真出现换行时，一条日志会被拆成好几行，按关键词 grep 就会断在
 *       中间，而那正是最需要完整上下文的时候。</li>
 *   <li><b>最后按长度截断</b>，并在末尾注明原文多少字节，避免有人以为报文就这么长。</li>
 * </ol>
 */
public final class BodyDumper {

    /** 日志里最多放这么多个字符。云效的合并请求报文通常几 KB，能完整放下。 */
    public static final int MAX_CHARS = 32768;

    /**
     * 抓出所有"字符串键 : 字符串值"。
     *
     * <p>用一条通用正则抓，再用 {@link #isSecretKey} 判断这个键要不要打码 ——
     * 而不是把关键字写进正则里。原因：写进正则时 {@code assignee}（审批人）
     * 会因为含有 "sign" 被误判成密钥字段，把审批人姓名糊掉，排查时反而看不到关键信息。</p>
     */
    private static final Pattern JSON_STRING_FIELD = Pattern.compile(
            "(\"([A-Za-z0-9_-]+)\"\\s*:\\s*)\"((?:[^\"\\\\]|\\\\.)*)\"");

    private BodyDumper() {
        // 工具类，不实例化
    }

    /**
     * 把报文字节压成一行可读文本。
     *
     * @param payload 报文字节，可为 null
     * @return 不会为 null；空报文返回 {@code (空报文)}
     */
    public static String dump(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "(空报文)";
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        String masked = maskJsonSecrets(text);
        String oneLine = toOneLine(masked);
        if (oneLine.length() <= MAX_CHARS) {
            return oneLine;
        }
        return oneLine.substring(0, MAX_CHARS)
                + "...(已截断，原文 " + payload.length + " 字节)";
    }

    /**
     * 这个 JSON 键是不是密钥类字段。
     *
     * @param key 键名，如 {@code access_token}、{@code sign}、{@code assignee}
     * @return true 表示它的值要打码
     */
    static boolean isSecretKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("token") || lower.contains("secret") || lower.contains("password")
                || lower.contains("passwd") || lower.contains("credential") || lower.contains("signature")) {
            return true;
        }
        // sign 必须是一个完整的词：sign / _sign / -sign。
        // 只是包含 "sign" 不算 —— assignee、design、signature 里都有它。
        return lower.equals("sign") || lower.endsWith("_sign") || lower.endsWith("-sign");
    }

    /** 把 JSON 里敏感键的值换成打码后的值，其余原样。 */
    private static String maskJsonSecrets(String text) {
        Matcher matcher = JSON_STRING_FIELD.matcher(text);
        StringBuffer out = new StringBuffer(text.length());
        while (matcher.find()) {
            String replacement;
            if (isSecretKey(matcher.group(2))) {
                replacement = matcher.group(1) + '"' + Secrets.mask(matcher.group(3)) + '"';
            } else {
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 换行、制表符转义成字面形式，保证一条消息只占一行。 */
    private static String toOneLine(String text) {
        StringBuilder oneLine = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                // \r\n 只转一个 \n，避免出现 "\\n\\n"
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    continue;
                }
                oneLine.append("\\n");
            } else if (c == '\n') {
                oneLine.append("\\n");
            } else if (c == '\t') {
                oneLine.append("\\t");
            } else {
                oneLine.append(c);
            }
        }
        return oneLine.toString();
    }
}

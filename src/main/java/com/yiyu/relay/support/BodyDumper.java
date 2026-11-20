package com.yiyu.relay.support;

import java.nio.charset.StandardCharsets;

/**
 * 把报文正文压成一行日志。
 *
 * <p><b>正文原样打印，不做脱敏</b>。理由：出问题时唯一的复现手段是拿原文重放一次，
 * 报文被改过（哪怕只是打掉几个字段）就重放不了了。正文里出现的密钥是自己的，
 * 需要时补上即可；日志文件本身已是 700/600 权限、只留 30 天。</p>
 *
 * <p>只做两件事：</p>
 * <ol>
 *   <li><b>压成一行</b>：把回车换行转义成 {@code \n} 的字面形式。云效报文是压过一行的
 *       JSON，但不保证 —— 真出现换行时，一条日志会被拆成好几行，按关键词 grep 就会断在
 *       中间，而那正是最需要完整上下文的时候。</li>
 *   <li><b>按长度截断</b>，并在末尾注明原文多少字节。日志行不是报文存储，
 *       完整原文在 {@code queue/} 下的 json 文件里，那才是重放要用的东西。</li>
 * </ol>
 */
public final class BodyDumper {

    /**
     * 日志里最多放这么多个字符。
     *
     * <p>云效的合并请求报文通常几 KB，正常都能完整放下；超过这个量基本是有人在往
     * 这个端口塞别的东西，压行只会白占日志盘。</p>
     */
    public static final int MAX_CHARS = 32768;

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
        String oneLine = toOneLine(new String(payload, StandardCharsets.UTF_8));
        if (oneLine.length() <= MAX_CHARS) {
            return oneLine;
        }
        return oneLine.substring(0, MAX_CHARS)
                + "...(日志截断，原文 " + payload.length + " 字节，完整原文在 queue 目录的文件里)";
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

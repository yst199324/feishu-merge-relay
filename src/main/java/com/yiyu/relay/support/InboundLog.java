package com.yiyu.relay.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServletServerHttpRequest;

import javax.servlet.http.HttpServletRequest;

/**
 * 入口留档：每收到一次推送，先记一条完整日志，再谈别的。
 *
 * <p>放在验签**之前**调用。理由：密钥填错、网关把请求头滤掉、有人拿扫描器敲门，
 * 这三种情况以前只会留下一行"来源 IP"，报文内容一个字都没记，事后完全无从下手。
 * 现在无论后面回 200 还是 403，前面都已经有一条带着全部请求头和报文正文的记录。</p>
 *
 * <p>日志量：一条云效合并请求报文通常几 KB，一天几十条，可以忽略。
 * 超过 {@link BodyDumper#MAX_CHARS} 的会截断并注明原文大小。</p>
 */
public final class InboundLog {

    /** 单独的 logger 名：只看入口留档时就 {@code grep com.yiyu.relay.inbound}。 */
    private static final Logger INBOUND = LoggerFactory.getLogger("com.yiyu.relay.inbound");

    /** 反向代理常用这个头带真实来源 IP。 */
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private InboundLog() {
        // 工具类，不实例化
    }

    /**
     * 收到推送，报文已读进内存时调用。
     *
     * @param request 原始请求
     * @param payload 报文原始字节
     */
    public static void received(HttpServletRequest request, byte[] payload) {
        if (!INBOUND.isInfoEnabled()) {
            // 拼这一大串要遍历请求头和整份报文，关掉 INFO 时不该白花这个力气
            return;
        }
        INBOUND.info("收到推送 from={} realIp={} {} {} headers={} bytes={} body={}",
                request.getRemoteAddr(),
                clientIp(request),
                request.getMethod(),
                request.getRequestURI(),
                HeaderDumper.dump(new ServletServerHttpRequest(request).getHeaders()),
                payload.length,
                BodyDumper.dump(payload));
    }

    /**
     * 报文大到连读都不准备读时调用（声明长度已超上限）。
     *
     * <p>这一条没有正文 —— 正因为它太大才不读。至少留下请求头和声明长度，
     * 能判断是谁在发、发了多大。</p>
     *
     * @param request    原始请求
     * @param declared   声明长度（可能是 -1，表示分块传输）
     * @param maxBytes   配置的上限
     */
    public static void tooLarge(HttpServletRequest request, long declared, long maxBytes) {
        INBOUND.info("收到推送（超限未读） from={} realIp={} {} {} headers={} declaredBytes={} maxBytes={}",
                request.getRemoteAddr(),
                clientIp(request),
                request.getMethod(),
                request.getRequestURI(),
                HeaderDumper.dump(new ServletServerHttpRequest(request).getHeaders()),
                declared,
                maxBytes);
    }

    /**
     * 真实来源：优先取反向代理给的 {@code X-Forwarded-For} 第一段。
     *
     * <p>只取第一段，是因为后面几段是各级代理自己加的，可以伪造。
     * 没有代理就直接用连接地址。</p>
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded == null || forwarded.trim().isEmpty()) {
            return request.getRemoteAddr();
        }
        int comma = forwarded.indexOf(',');
        return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
    }
}

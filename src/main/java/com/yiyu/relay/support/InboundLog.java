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
     * <p>这里刻意<b>不加</b> {@code isInfoEnabled()} 提前返回：级别已经在
     * {@code logback-spring.xml} 里钉成 INFO、启动自检还会再核一遍。留着那种判断，
     * 会让后来的人以为"关掉 INFO 也不影响留档"，而实际上正相反 ——
     * 这条日志就是留档本身。</p>
     *
     * @param request 原始请求
     * @param payload 报文原始字节
     */
    public static void received(HttpServletRequest request, byte[] payload) {
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
     * 读报文中途失败时调用（客户端断开、连接重置）。
     *
     * <p>拿不到完整正文，但<b>请求头必须留下</b>：否则这条请求在日志里只剩一行
     * 「读取报文失败」，连是谁发的、带了哪些头都不知道 —— 正是这个改造要消灭的情形。</p>
     *
     * <p>声明长度也一起记：它和"读失败"这个事实放在一起，能区分
     * "客户端说到一半跑了"和"我们这边读的时候出了问题"。</p>
     *
     * <p>用 WARN 不用 INFO：这是异常路径，不该跟正常留档一起被级别调高带走。</p>
     *
     * @param request 原始请求
     * @param cause   读失败的原因，可为 null
     */
    public static void readFailed(HttpServletRequest request, Exception cause) {
        INBOUND.warn("收到推送（读报文失败，正文不完整） from={} realIp={} {} {} headers={} declaredBytes={} error={}",
                request.getRemoteAddr(),
                clientIp(request),
                request.getMethod(),
                request.getRequestURI(),
                HeaderDumper.dump(new ServletServerHttpRequest(request).getHeaders()),
                request.getContentLengthLong(),
                cause == null || cause.getMessage() == null ? "（无异常信息）" : cause.getMessage());
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

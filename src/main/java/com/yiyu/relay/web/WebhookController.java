package com.yiyu.relay.web;

import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.queue.InboundMessage;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.DeliveryIds;
import com.yiyu.relay.support.HeaderNames;
import com.yiyu.relay.support.InboundLog;
import com.yiyu.relay.support.PayloadTooLargeException;
import com.yiyu.relay.support.Payloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 接住云效推送（设计文档 6.1）。
 *
 * <p>顺序是刻意安排的：**先把报文读下来并留档，再验签**。反过来的话，密钥填错、
 * 网关滤掉请求头、扫描器敲门这三种情况，报文内容一个字都不会留下，事后只能对着
 * 一行"来源 IP"猜。代价是未验签的请求也会被读进内存，但读取有硬上限
 * （{@code max-payload-bytes}），读超了就中断，撑不爆。</p>
 *
 * <p>返回码是给云效看的唯一反馈，每一种都必须准确：</p>
 * <ul>
 *   <li>403 密钥不匹配，不落盘</li>
 *   <li>400 缺投递编号，不落盘（多半是网关把未声明的请求头滤掉了，见 5.2）</li>
 *   <li>413 报文超限，不落盘</li>
 *   <li>500 写文件失败 —— 必须如实返回，不能假装成功（17 章"磁盘写不进去"）</li>
 *   <li>200 已落盘，或同一编号已在队列里（重复投递）</li>
 * </ul>
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /** 云效 Webhook 路径，一个字都不能改（云效后台填的就是这个）。 */
    public static final String PATH = "/webhook/yunxiao";

    private final RelayProperties properties;
    private final QueueStore queueStore;

    public WebhookController(RelayProperties properties, QueueStore queueStore) {
        this.properties = properties;
        this.queueStore = queueStore;
    }

    /**
     * 接收云效推送。
     *
     * @param request 原始请求（为了拿到原始字节，不用 @RequestBody 反序列化）
     * @return 给云效的状态码
     */
    @PostMapping(PATH)
    public ResponseEntity<String> receive(HttpServletRequest request) {
        long maxPayloadBytes = properties.getMaxPayloadBytes();
        long declaredBytes = request.getContentLengthLong();
        // 声明就超限的连读都不读（防一个几 GB 的报文把堆和磁盘占满）
        if (declaredBytes > maxPayloadBytes) {
            InboundLog.tooLarge(request, declaredBytes, maxPayloadBytes);
            log.error("报文超限，拒绝落盘：声明 {} 字节，上限 {} 字节", declaredBytes, maxPayloadBytes);
            return respond(HttpStatus.PAYLOAD_TOO_LARGE, "报文超过上限");
        }

        byte[] payload;
        try {
            payload = Payloads.read(request.getInputStream(), maxPayloadBytes);
        } catch (PayloadTooLargeException e) {
            // 声明长度在限额内、实际读数超了：分块传输，或者头里写了个假长度
            InboundLog.tooLarge(request, declaredBytes, e.getMaxBytes());
            log.error("报文超限，拒绝落盘：上限 {} 字节", e.getMaxBytes());
            return respond(HttpStatus.PAYLOAD_TOO_LARGE, "报文超过上限");
        } catch (IOException e) {
            // 正文没读全（客户端说到一半断了），但请求头必须留下：否则这条请求在日志里
            // 只剩一行"读取报文失败"，连是谁发的、带了哪些头都不知道。
            InboundLog.readFailed(request, e);
            log.error("读取报文失败，按投递失败返回 500（{}）", e.getMessage());
            return respond(HttpStatus.INTERNAL_SERVER_ERROR, "读取报文失败");
        }

        // 留档在验签之前：这一条之后无论回什么码，请求头和报文原文都已经在日志里了
        InboundLog.received(request, payload);

        if (!tokenMatches(request)) {
            log.warn("云效推送被拒绝：{} 和 {} 两个头都试过了（优先前者），没带或都不匹配配置的密钥（来源 {}）",
                    HeaderNames.TOKEN, HeaderNames.TOKEN_LEGACY, request.getRemoteAddr());
            return respond(HttpStatus.FORBIDDEN, "token 不匹配");
        }
        return dispatch(payload, request);
    }

    /** 取投递编号并落盘。缺编号不落盘，也绝不用空编号入队。 */
    private ResponseEntity<String> dispatch(byte[] payload, HttpServletRequest request) {
        String rawDeliveryId = request.getHeader(HeaderNames.DELIVERY);
        String deliveryId = DeliveryIds.sanitize(rawDeliveryId);
        if (DeliveryIds.isBlank(deliveryId)) {
            return rejectMissingDelivery(rawDeliveryId);
        }
        return store(payload, deliveryId, request);
    }

    /**
     * 落盘。查重要在写之前做，且 pending / retry / dead 三个目录都查
     * （设计文档 14.14：只查 pending 会在消息移进 retry 后漏判，导致重复发卡）。
     */
    private ResponseEntity<String> store(byte[] payload, String deliveryId, HttpServletRequest request) {
        if (queueStore.existsByDeliveryId(deliveryId)) {
            log.info("重复投递，跳过落盘：编号 {}，{} 字节", deliveryId, payload.length);
            return respond(HttpStatus.OK, "重复投递，已跳过");
        }
        InboundMessage message = new InboundMessage(
                deliveryId,
                request.getHeader(HeaderNames.EVENT),
                request.getHeader(HeaderNames.CONTENT_TYPE),
                payload,
                System.currentTimeMillis());
        try {
            QueueFile queued = queueStore.enqueue(message);
            // 文件路径要打出来：出问题时"重放一次"就是拿这个文件直接 curl 内部入口，
            // 不用去目录里翻，也避免翻到事后被别的消息顶替。
            log.info("已入队：编号 {}，事件 {}，{} 字节，文件 {}",
                    deliveryId, request.getHeader(HeaderNames.EVENT), payload.length, queued.getPath());
        } catch (IOException e) {
            // 不能假装成功：假装成功等于消息彻底消失（云效不会再投）
            log.error("落盘失败，返回 500 让云效看到投递失败：编号 {}，{} 字节（{}）",
                    deliveryId, payload.length, e.getMessage());
            return respond(HttpStatus.INTERNAL_SERVER_ERROR, "落盘失败");
        }
        return respond(HttpStatus.OK, "已入队");
    }

    /**
     * 缺投递编号：400，不落盘。
     *
     * <p>请求头和报文正文已经由 {@link InboundLog} 记过了，这里只补排查方向 ——
     * 十次有九次是中间的 Nginx 把未声明的请求头滤掉了。</p>
     */
    private ResponseEntity<String> rejectMissingDelivery(String rawDeliveryId) {
        log.error("云效推送缺少 {}，不落盘。收到的原始值={}。"
                        + "若云效后台显示已发出，多半是中间的 Nginx / 防火墙把未声明的请求头滤掉了，"
                        + "检查 proxy_set_header 是否透传未知头",
                HeaderNames.DELIVERY, rawDeliveryId);
        return respond(HttpStatus.BAD_REQUEST, "缺少 " + HeaderNames.DELIVERY);
    }

    /**
     * 校验密钥。密钥为空、或还是 yml 里的占位文字时一律拒绝 —— 那两种情况等于门没锁，
     * 任何人都能 POST 进来让合并项目发卡片。
     *
     * <p>请求里的密钥由 {@link #resolveToken} 按优先级取值：云效历史上发过两种头，
     * 合并项目的 WebhookGuard 两种都认，这里必须跟着认。</p>
     */
    private boolean tokenMatches(HttpServletRequest request) {
        if (!properties.hasUsableWebhookToken()) {
            log.error("relay.webhook-token 为空或仍是占位值，拒绝所有云效推送。请在 .env 里设置 YUNXIAO_WEBHOOK_TOKEN");
            return false;
        }
        String expected = properties.getWebhookToken();
        String provided = resolveToken(request);
        if (provided == null) {
            return false;
        }
        // 定长比较，避免用响应时间差逐字符猜密钥
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按优先级取请求里的密钥：新头优先，新头没带才回落到老头。
     *
     * <p>刻意不做"任一匹配即通过"：两个头都带时以新头为准。否则只要有一个头带对就放行，
     * 等于把两个头里更弱的一个变成后门 —— 那是安全洞，不是兼容。</p>
     *
     * @param request 原始请求
     * @return 请求里的密钥；两个头都没带时返回 null
     */
    private String resolveToken(HttpServletRequest request) {
        String primary = request.getHeader(HeaderNames.TOKEN);
        if (!DeliveryIds.isBlank(primary)) {
            return primary;
        }
        String legacy = request.getHeader(HeaderNames.TOKEN_LEGACY);
        if (!DeliveryIds.isBlank(legacy)) {
            return legacy;
        }
        return null;
    }

    private ResponseEntity<String> respond(HttpStatus status, String body) {
        return ResponseEntity.status(status).body(body);
    }
}

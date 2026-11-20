package com.yiyu.relay.web;

import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.queue.InboundMessage;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.DeliveryIds;
import com.yiyu.relay.support.HeaderDumper;
import com.yiyu.relay.support.HeaderNames;
import com.yiyu.relay.support.PayloadTooLargeException;
import com.yiyu.relay.support.Payloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.server.ServletServerHttpRequest;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 接住云效推送（设计文档 6.1）。
 *
 * <p>只做四件检查再交给队列：密钥对不对、报文是不是太大、有没有带投递编号、
 * 这条是不是已经收过。不解析报文、不校验业务含义 —— 那是合并项目的事。</p>
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
        if (!tokenMatches(request)) {
            log.warn("云效推送被拒绝：{} 不匹配（来源 {}）", HeaderNames.TOKEN, request.getRemoteAddr());
            return respond(HttpStatus.FORBIDDEN, "token 不匹配");
        }
        String rawDeliveryId = request.getHeader(HeaderNames.DELIVERY);
        if (DeliveryIds.isBlank(rawDeliveryId)) {
            return rejectMissingDelivery(request);
        }
        String deliveryId = DeliveryIds.sanitize(rawDeliveryId);
        if (DeliveryIds.isBlank(deliveryId)) {
            return rejectMissingDelivery(request);
        }
        return enqueue(request, deliveryId);
    }

    /** 校验时报文大小，超限直接 413，连读都不读。 */
    private ResponseEntity<String> enqueue(HttpServletRequest request, String deliveryId) {
        long maxPayloadBytes = properties.getMaxPayloadBytes();
        if (request.getContentLengthLong() > maxPayloadBytes) {
            log.error("报文超限，拒绝落盘：编号 {}，声明长度 {} 字节，上限 {} 字节",
                    deliveryId, request.getContentLengthLong(), maxPayloadBytes);
            return respond(HttpStatus.PAYLOAD_TOO_LARGE, "报文超过上限");
        }
        byte[] payload;
        try {
            payload = Payloads.read(request.getInputStream(), maxPayloadBytes);
        } catch (PayloadTooLargeException e) {
            log.error("报文超限，拒绝落盘：编号 {}，上限 {} 字节", deliveryId, e.getMaxBytes());
            return respond(HttpStatus.PAYLOAD_TOO_LARGE, "报文超过上限");
        } catch (IOException e) {
            log.error("读取报文失败，按投递失败返回 500：编号 {}（{}）", deliveryId, e.getMessage());
            return respond(HttpStatus.INTERNAL_SERVER_ERROR, "读取报文失败");
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
            queueStore.enqueue(message);
        } catch (IOException e) {
            // 不能假装成功：假装成功等于消息彻底消失（云效不会再投）
            log.error("落盘失败，返回 500 让云效看到投递失败：编号 {}，{} 字节（{}）",
                    deliveryId, payload.length, e.getMessage());
            return respond(HttpStatus.INTERNAL_SERVER_ERROR, "落盘失败");
        }
        log.info("已入队：编号 {}，事件 {}，{} 字节",
                deliveryId, request.getHeader(HeaderNames.EVENT), payload.length);
        return respond(HttpStatus.OK, "已入队");
    }

    /** 缺投递编号：400 + 把收到的请求头打出来（脱敏），不落盘、不入队。 */
    private ResponseEntity<String> rejectMissingDelivery(HttpServletRequest request) {
        String headers = HeaderDumper.dump(new ServletServerHttpRequest(request).getHeaders());
        log.error("云效推送缺少 {}，不落盘。收到的请求头：{}。"
                        + "若云效后台显示已发出，多半是中间的 Nginx / 防火墙把未声明的请求头滤掉了，"
                        + "检查 proxy_set_header 是否透传未知头",
                HeaderNames.DELIVERY, headers);
        return respond(HttpStatus.BAD_REQUEST, "缺少 " + HeaderNames.DELIVERY);
    }

    /**
     * 校验密钥。配置为空时一律拒绝 —— 空密钥等于门没锁，
     * 任何人都能 POST 进来让合并项目发卡片。
     */
    private boolean tokenMatches(HttpServletRequest request) {
        String expected = properties.getWebhookToken();
        if (DeliveryIds.isBlank(expected)) {
            log.error("relay.webhook-token 未配置，拒绝所有云效推送。请在 .env 里设置 YUNXIAO_WEBHOOK_TOKEN");
            return false;
        }
        String provided = request.getHeader(HeaderNames.TOKEN);
        if (provided == null) {
            return false;
        }
        // 定长比较，避免用响应时间差逐字符猜密钥
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<String> respond(HttpStatus status, String body) {
        return ResponseEntity.status(status).body(body);
    }
}

package com.yiyu.relay.client;

/**
 * 一次待转发的消息：报文原始字节 + 需要透传的三个头。
 *
 * <p>报文之所以是 {@code byte[]} 而不是对象：设计文档 6.2 要求"原样透传，不做任何重新序列化"。
 * 一旦反序列化再序列化，字段顺序、空值处理都会变，合并项目解析出来的东西未必一样。</p>
 */
public final class OutgoingMessage {

    private final String deliveryId;
    private final String eventName;
    private final String contentType;
    private final byte[] payload;

    public OutgoingMessage(String deliveryId, String eventName, String contentType, byte[] payload) {
        this.deliveryId = deliveryId;
        this.eventName = eventName;
        this.contentType = contentType;
        this.payload = payload;
    }

    /** 云效投递编号，写进 {@code X-Codeup-Delivery}。头名不能改，见设计文档 6.2。 */
    public String getDeliveryId() {
        return deliveryId;
    }

    /** 云效事件名，写进 {@code X-Codeup-Event}，合并项目日志里要打。可为 null。 */
    public String getEventName() {
        return eventName;
    }

    /** 原样透传的 Content-Type（含 charset）。为空时调用方应回落默认值。 */
    public String getContentType() {
        return contentType;
    }

    /** 报文原文。 */
    public byte[] getPayload() {
        return payload;
    }

    /** 报文长度，日志和限额判断用。 */
    public int payloadLength() {
        return payload == null ? 0 : payload.length;
    }
}

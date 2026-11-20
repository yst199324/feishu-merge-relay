package com.yiyu.relay.queue;

/**
 * 一条要落盘的消息：清洗过的编号 + 报文原始字节 + 需要随报文一起保存的请求头。
 *
 * <p>为什么要把 Content-Type 和事件名一起存：这两项在转发时必须原样带回
 * （Content-Type 带 charset，否则中文乱码；事件名合并项目日志里要打），
 * 而报文文件里只能放报文原文，不允许塞信封结构，所以就单独存一份元数据。</p>
 */
public final class InboundMessage {

    private final String deliveryId;
    private final String eventName;
    private final String contentType;
    private final byte[] payload;
    private final long arrivedAtMillis;

    public InboundMessage(String deliveryId, String eventName, String contentType,
                          byte[] payload, long arrivedAtMillis) {
        this.deliveryId = deliveryId;
        this.eventName = eventName;
        this.contentType = contentType;
        this.payload = payload;
        this.arrivedAtMillis = arrivedAtMillis;
    }

    /** 已清洗的投递编号（只含字母、数字、短横线、下划线）。 */
    public String getDeliveryId() {
        return deliveryId;
    }

    /** 云效事件名，可能为 null（云效没带这个头时）。 */
    public String getEventName() {
        return eventName;
    }

    /** 原始 Content-Type（含 charset），可能为 null。 */
    public String getContentType() {
        return contentType;
    }

    public byte[] getPayload() {
        return payload;
    }

    /** 首次到达毫秒，写进文件名。 */
    public long getArrivedAtMillis() {
        return arrivedAtMillis;
    }
}

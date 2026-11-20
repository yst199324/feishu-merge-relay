package com.yiyu.relay.queue;

/**
 * 与报文一起保存的少量请求头信息（事件名、原始 Content-Type）。
 *
 * <p>报文文件本身只放报文原文，绝不塞信封结构 —— 否则运维 {@code curl} 那个文件
 * 打内部入口就不好使了，也违背"原样透传"的初衷。这两项另存一份小文件即可。</p>
 */
public final class MessageMeta {

    /** 元数据缺失时的空值：转发时回落默认 Content-Type，且不带事件头。 */
    public static final MessageMeta EMPTY = new MessageMeta(null, null);

    private final String eventName;
    private final String contentType;

    public MessageMeta(String eventName, String contentType) {
        this.eventName = eventName;
        this.contentType = contentType;
    }

    public String getEventName() {
        return eventName;
    }

    public String getContentType() {
        return contentType;
    }
}

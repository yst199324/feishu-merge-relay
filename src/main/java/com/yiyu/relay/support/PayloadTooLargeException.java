package com.yiyu.relay.support;

/**
 * 报文超过上限（设计文档 14.4）。用受检异常不合适 —— 它只在一个地方被捕获，
 * 而且调用方除了返回 413 也做不了别的。
 */
public class PayloadTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long maxBytes;

    public PayloadTooLargeException(long maxBytes) {
        super("报文超过上限 " + maxBytes + " 字节");
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}

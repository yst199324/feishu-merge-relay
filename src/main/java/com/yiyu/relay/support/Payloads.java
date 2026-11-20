package com.yiyu.relay.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 带上限地读取请求体。
 *
 * <p>为什么不直接用 {@code request.getInputStream()} 全读再判断长度：一个几百 MB 的报文
 * 会把堆和磁盘瞬间占满（设计文档 14.4 要防的就是这件事）。这里边读边计数，
 * 一超限立刻中断，最多多占一个 chunk 的内存。</p>
 */
public final class Payloads {

    private static final int CHUNK_SIZE = 8192;

    private Payloads() {
        // 工具类，不实例化
    }

    /**
     * 读请求体。
     *
     * @param inputStream 请求体流（方法内不负责关闭，由容器管理）
     * @param maxBytes    允许的最大字节数
     * @return 报文字节
     * @throws IOException              读取失败（网络中断等）
     * @throws PayloadTooLargeException 超过上限
     */
    public static byte[] read(InputStream inputStream, long maxBytes) throws IOException {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("报文上限必须为正数，当前：" + maxBytes);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[CHUNK_SIZE];
        long total = 0L;
        int read;
        while ((read = inputStream.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}

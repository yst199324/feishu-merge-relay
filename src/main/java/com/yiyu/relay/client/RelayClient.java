package com.yiyu.relay.client;

import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.support.DeliveryIds;
import com.yiyu.relay.support.HeaderNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 转发客户端：一次调用只做一次 HTTP 请求，并把结果归成四类。
 *
 * <p>用 JDK 自带的 {@link HttpURLConnection} 而不是 RestTemplate / OkHttp：
 * 这里只需要"发一个 POST、看状态码、读一小段响应体"，多引一个客户端库
 * 就多一份依赖和三处可配置的超时语义。超时值全部从配置来，读超时默认 120 秒
 * （合并项目要同步串行查最多三次云效接口再发一次卡片，最坏约 85 秒，
 * 设短了会出现"它其实成功了、我们判成失败"）。</p>
 */
@Component
public class RelayClient implements RelayTarget {

    private static final Logger log = LoggerFactory.getLogger(RelayClient.class);

    /** 只读响应体前这么多字节用于判断凭据问题，避免下游返回大页面时占内存。 */
    private static final int BODY_READ_LIMIT = 4096;

    private final RelayProperties properties;
    private final StatusCodePolicy statusCodePolicy;
    private final CredentialFailureDetector credentialFailureDetector;

    public RelayClient(RelayProperties properties,
                       StatusCodePolicy statusCodePolicy,
                       CredentialFailureDetector credentialFailureDetector) {
        this.properties = properties;
        this.statusCodePolicy = statusCodePolicy;
        this.credentialFailureDetector = credentialFailureDetector;
    }

    @Override
    public ForwardResult send(OutgoingMessage message) {
        if (message == null) {
            return ForwardResult.transportFailure("消息为空，未发出请求");
        }
        if (DeliveryIds.isBlank(message.getDeliveryId())) {
            // 文件名里一定带编号，走到这里是程序错误；按永久失败处理，不让它反复重试刷日志
            log.error("转发被拒绝：消息没有投递编号（报文 {} 字节）", message.payloadLength());
            return ForwardResult.permanent(ForwardResult.NO_STATUS, "缺少投递编号", false);
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(HeaderNames.DELIVERY, message.getDeliveryId());
        if (!DeliveryIds.isBlank(message.getEventName())) {
            headers.put(HeaderNames.EVENT, message.getEventName());
        }
        headers.put(HeaderNames.CONTENT_TYPE, contentTypeOf(message));
        return post(headers, message.getPayload());
    }

    /**
     * 启动探测用：发一个空报文，只为确认"内部入口在不在"。
     *
     * <p>不带 {@code X-Codeup-Delivery}：这不是一条真实消息，不该被合并项目当成投递处理；
     * 拿到 400 恰好说明接口是通的（它在抱怨报文），这是设计文档 14.8 认可的"可达"结论。</p>
     *
     * @return 转发结论（探测失败也不抛异常，只返回结论）
     */
    public ForwardResult probe() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(HeaderNames.CONTENT_TYPE, HeaderNames.DEFAULT_CONTENT_TYPE);
        return post(headers, new byte[0]);
    }

    /** Content-Type 必须原样透传（含 charset），没带时才回落默认值。 */
    private String contentTypeOf(OutgoingMessage message) {
        return DeliveryIds.isBlank(message.getContentType())
                ? HeaderNames.DEFAULT_CONTENT_TYPE
                : message.getContentType();
    }

    private ForwardResult post(Map<String, String> headers, byte[] payload) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection();
            applyHeaders(connection, headers);
            writePayload(connection, payload);
            int httpStatus = connection.getResponseCode();
            String body = readBody(connection, httpStatus);
            return classify(httpStatus, body);
        } catch (SocketTimeoutException e) {
            return ForwardResult.transportFailure("超时: " + e.getMessage());
        } catch (IOException e) {
            return ForwardResult.transportFailure("连接失败: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection() throws IOException {
        URL url = new URL(properties.getTarget().getUrl());
        // 内部入口是机器对机器（默认就是 127.0.0.1），不该被 JVM 的 http.proxyHost
        // 或环境里的代理设置带偏 —— 走了代理会连到"代理那台机器"的 127.0.0.1，报连接失败，
        // 而日志看起来像合并项目没起来。setUseCaches(false) 跟代理无关，别拿它顶。
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(properties.getTarget().getConnectTimeoutMs());
        connection.setReadTimeout(properties.getTarget().getReadTimeoutMs());
        connection.setUseCaches(false);
        return connection;
    }

    private void applyHeaders(HttpURLConnection connection, Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
    }

    private void writePayload(HttpURLConnection connection, byte[] payload) throws IOException {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        connection.setFixedLengthStreamingMode(safePayload.length);
        OutputStream output = connection.getOutputStream();
        try {
            output.write(safePayload);
            output.flush();
        } finally {
            output.close();
        }
    }

    /** 读响应体。2xx 走 inputStream，4xx/5xx 走 errorStream（否则会抛 IOException）。 */
    private String readBody(HttpURLConnection connection, int httpStatus) {
        InputStream stream = null;
        try {
            stream = httpStatus >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return "";
            }
            return new String(readLimited(stream), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 读响应体失败不影响结论：状态码已经拿到了，只记一条 debug
            log.debug("读取响应体失败，状态码 {}：{}", httpStatus, e.getMessage());
            return "";
        } finally {
            closeQuietly(stream);
        }
    }

    private byte[] readLimited(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int total = 0;
        int read;
        while (total < BODY_READ_LIMIT && (read = stream.read(chunk)) != -1) {
            int length = Math.min(read, BODY_READ_LIMIT - total);
            buffer.write(chunk, 0, length);
            total += length;
        }
        return buffer.toByteArray();
    }

    private void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("关闭响应流失败：{}", e.getMessage());
        }
    }

    /** 按白名单归类，四种结论一一对应 worker 的四种动作。 */
    private ForwardResult classify(int httpStatus, String body) {
        ForwardOutcome outcome = statusCodePolicy.classify(httpStatus);
        if (outcome == ForwardOutcome.SUCCESS) {
            // 带上响应体摘要：合并项目的 200 里还有 outcome=DONE / IGNORED 的区别，排障时要能对上
            return ForwardResult.success(httpStatus, body);
        }
        if (outcome == ForwardOutcome.ENDPOINT_MISSING) {
            return ForwardResult.endpointMissing(httpStatus);
        }
        if (outcome == ForwardOutcome.PERMANENT) {
            boolean credentialFailure = credentialFailureDetector.indicatesCredentialFailure(httpStatus, body);
            return ForwardResult.permanent(httpStatus, body, credentialFailure);
        }
        return ForwardResult.retryable(httpStatus, body);
    }
}

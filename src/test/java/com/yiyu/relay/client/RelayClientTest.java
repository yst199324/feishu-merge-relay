package com.yiyu.relay.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.support.HeaderNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 返回码归类测试（设计文档 6.2 的白名单）。
 *
 * <p>用 JDK 自带的 {@link HttpServer} 起一个假的下游，不引入 okhttp / mockwebserver ——
 * 这个服务只需要"发一个 POST、看状态码"，没必要为测试多拉一份依赖。</p>
 */
class RelayClientTest {

    private static final String PATH = "/internal/yunxiao";
    private static final byte[] PAYLOAD = "{\"object_kind\":\"merge_request\",\"标题\":\"中文\"}"
            .getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private RelayClient relayClient;

    private final AtomicReference<String> receivedDelivery = new AtomicReference<String>();
    private final AtomicReference<String> receivedEvent = new AtomicReference<String>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<String>();
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<byte[]>(new byte[0]);

    private volatile int responseStatus = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, new FakeTarget());
        server.setExecutor(null);
        server.start();

        RelayProperties properties = new RelayProperties();
        properties.getTarget().setUrl("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
        properties.getTarget().setConnectTimeoutMs(1000);
        properties.getTarget().setReadTimeoutMs(2000);
        relayClient = new RelayClient(properties, new StatusCodePolicy(), new CredentialFailureDetector());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("200 → 成功，并留下响应体摘要（合并项目用 outcome 区分 DONE / IGNORED）")
    void success() {
        responseStatus = 200;
        responseBody = "{\"outcome\":\"IGNORED\",\"note\":\"跳过：这条投递已经发过卡片了\"}";
        ForwardResult result = relayClient.send(message());
        assertEquals(ForwardOutcome.SUCCESS, result.getOutcome());
        assertEquals(200, result.getHttpStatus());
        assertTrue(result.describe().contains("成功"));
        assertTrue(result.getDetail().contains("IGNORED"), "响应体要留摘要，排障时能和合并项目的处理记录对上");
    }

    @Test
    @DisplayName("201 / 204 也算成功")
    void createdAndNoContentAreSuccess() {
        responseStatus = 201;
        assertEquals(ForwardOutcome.SUCCESS, relayClient.send(message()).getOutcome());
        responseStatus = 204;
        assertEquals(ForwardOutcome.SUCCESS, relayClient.send(message()).getOutcome());
    }

    @Test
    @DisplayName("三个头原样透传，报文一个字节都不改")
    void headersAndPayloadPassedThrough() {
        responseStatus = 200;
        relayClient.send(message());

        assertEquals("delivery-1", receivedDelivery.get());
        assertEquals("merge_request", receivedEvent.get());
        assertEquals("application/json;charset=UTF-8", receivedContentType.get(),
                "Content-Type 必须原样透传，写死会丢 charset 导致中文乱码");
        assertArrayEquals(PAYLOAD, receivedBody.get(), "报文必须原样透传，不能重新序列化");
    }

    @Test
    @DisplayName("400 → 永久失败（不重试）")
    void badRequestIsPermanent() {
        responseStatus = 400;
        responseBody = "报文解析失败：取不到仓库号";
        ForwardResult result = relayClient.send(message());
        assertEquals(ForwardOutcome.PERMANENT, result.getOutcome());
        assertFalse(result.isCredentialFailure());
    }

    @Test
    @DisplayName("400 且说明是凭据问题 → 永久失败 + 标记 auth")
    void credentialFailureIsFlagged() {
        responseStatus = 400;
        responseBody = "云效凭据失效，请更换 x-yunxiao-token 后手工补发";
        ForwardResult result = relayClient.send(message());
        assertEquals(ForwardOutcome.PERMANENT, result.getOutcome());
        assertTrue(result.isCredentialFailure(), "死信原因应记为 auth");
    }

    @Test
    @DisplayName("413 → 永久失败")
    void payloadTooLargeIsPermanent() {
        responseStatus = 413;
        assertEquals(ForwardOutcome.PERMANENT, relayClient.send(message()).getOutcome());
    }

    @Test
    @DisplayName("404 / 405 → 内部入口没开，不得按可重试处理")
    void endpointMissingIsItsOwnOutcome() {
        responseStatus = 404;
        assertEquals(ForwardOutcome.ENDPOINT_MISSING, relayClient.send(message()).getOutcome());
        responseStatus = 405;
        assertEquals(ForwardOutcome.ENDPOINT_MISSING, relayClient.send(message()).getOutcome());
    }

    @Test
    @DisplayName("500 / 503 → 可重试，且不算成功")
    void serverErrorsAreRetryable() {
        responseStatus = 503;
        ForwardResult result = relayClient.send(message());
        assertEquals(ForwardOutcome.RETRYABLE, result.getOutcome());
        assertFalse(result.getOutcome().isSuccess(), "可重试失败不能算成功，否则 /health 里连续失败次数永远是 0");
        responseStatus = 500;
        assertEquals(ForwardOutcome.RETRYABLE, relayClient.send(message()).getOutcome());
    }

    @Test
    @DisplayName("连不上 → 可重试（没有状态码）")
    void connectionFailureIsRetryable() {
        server.stop(0);
        ForwardResult result = relayClient.send(message());
        assertEquals(ForwardOutcome.RETRYABLE, result.getOutcome());
        assertEquals(ForwardResult.NO_STATUS, result.getHttpStatus());
    }

    @Test
    @DisplayName("启动探测：400 说明接口是通的（可达），404 才算开关没开")
    void probeInterpretation() {
        responseStatus = 400;
        responseBody = "缺少 X-Codeup-Delivery";
        assertEquals(ForwardOutcome.PERMANENT, relayClient.probe().getOutcome(),
                "400 说明内部入口存在，属于『可达』");

        responseStatus = 404;
        assertEquals(ForwardOutcome.ENDPOINT_MISSING, relayClient.probe().getOutcome());
    }

    private OutgoingMessage message() {
        return new OutgoingMessage("delivery-1", "merge_request",
                "application/json;charset=UTF-8", PAYLOAD);
    }

    /** 假的下游：记录收到的头与报文，按当前设定返回状态码。 */
    private final class FakeTarget implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            receivedDelivery.set(exchange.getRequestHeaders().getFirst(HeaderNames.DELIVERY));
            receivedEvent.set(exchange.getRequestHeaders().getFirst(HeaderNames.EVENT));
            receivedContentType.set(exchange.getRequestHeaders().getFirst(HeaderNames.CONTENT_TYPE));
            receivedBody.set(readAll(exchange.getRequestBody()));
            respond(exchange);
        }

        private void respond(HttpExchange exchange) throws IOException {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            if (body.length == 0) {
                exchange.sendResponseHeaders(responseStatus, -1L);
            } else {
                exchange.sendResponseHeaders(responseStatus, body.length);
                OutputStream output = exchange.getResponseBody();
                try {
                    output.write(body);
                } finally {
                    output.close();
                }
            }
            exchange.close();
        }

        private byte[] readAll(InputStream input) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}

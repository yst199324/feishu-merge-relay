package com.yiyu.relay.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.support.InFlightDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨工程契约测试：钉住合并项目内部入口返回的**响应体原文**。
 *
 * <p>为什么单独一个类：这里断言的字符串是 {@code feishu-merge-bot} 那位工程师写在
 * {@code YunxiaoInternalController} 里的字面量，任何一方单方面改字段名、改措辞、
 * 把 {@code outcome} 挪到别的字段之后，我这边的 IN_FLIGHT 识别就会静默失效 ——
 * 而失效的表现只是"日志不该报警的地方报警了"，没人会注意到。所以把原文钉在测试里，
 * 对方一改，我这边的构建立刻红。</p>
 *
 * <p>对方已在 {@code YunxiaoInternalControllerTest.inFlightIs503} 反向钉了同一条，
 * 两侧互为守卫。</p>
 */
class TargetResponseContractTest {

    private static final String PATH = "/internal/yunxiao";
    private static final byte[] PAYLOAD = "{\"object_kind\":\"merge_request\"}".getBytes(StandardCharsets.UTF_8);

    /** 合并项目处理中：outcome 是第一个字段，值就是字面量 IN_FLIGHT。 */
    private static final String BODY_IN_FLIGHT =
            "{\"outcome\":\"IN_FLIGHT\",\"note\":\"处理中：这条投递还在处理，本次没有重复执行 "
                    + "delivery=9f1c2f4e-8a3b-4c5d-9e6f-0a1b2c3d4e5f\"}";

    /** 合并项目已发卡片（200）。 */
    private static final String BODY_DONE =
            "{\"outcome\":\"DONE\",\"note\":\"成功：已发卡片 repo=acme/web mr=42 投递=9f1c2f4e "
                    + "提交数=3 提交来源=push messageId=om_abc123\"}";

    /** 合并项目已处理过（200，去重命中）。 */
    private static final String BODY_IGNORED =
            "{\"outcome\":\"IGNORED\",\"note\":\"跳过：这条投递已经发过卡片了 "
                    + "delivery=9f1c2f4e-8a3b-4c5d-9e6f-0a1b2c3d4e5f\"}";

    /** 合并项目永久失败（400，凭据问题）。 */
    private static final String BODY_PERMANENT_FAIL =
            "{\"outcome\":\"PERMANENT_FAIL\",\"note\":\"失败：云效凭据失效，重试无用，"
                    + "换完令牌后手工补发。原始错误=401 Unauthorized\"}";

    /** 合并项目可重试失败（503）。 */
    private static final String BODY_RETRYABLE =
            "{\"outcome\":\"RETRYABLE\",\"note\":\"失败：处理这条投递时出错："
                    + "java.net.SocketTimeoutException: Read timed out\"}";

    private HttpServer server;
    private RelayClient relayClient;

    private volatile int responseStatus = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, new FixedResponseHandler());
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
    @DisplayName("IN_FLIGHT（503）→ 可重试，且被识别为『下游正在处理』，原文未被截断掉")
    void inFlightBodyIsRecognised() {
        responseStatus = 503;
        responseBody = BODY_IN_FLIGHT;
        ForwardResult result = relayClient.send(message());

        assertEquals(ForwardOutcome.RETRYABLE, result.getOutcome());
        assertTrue(InFlightDetector.isInFlight(result), "合并项目改了这个字面量的话，这条会立刻红");
        assertTrue(result.getDetail().contains("\"outcome\":\"IN_FLIGHT\""),
                "标记必须落在摘要保留的前若干字符内，否则识别规则会静默失效");
    }

    @Test
    @DisplayName("DONE（200）→ 成功，不会被误判成处理中")
    void doneBodyIsSuccessAndNotInFlight() {
        responseStatus = 200;
        responseBody = BODY_DONE;
        ForwardResult result = relayClient.send(message());

        assertEquals(ForwardOutcome.SUCCESS, result.getOutcome());
        assertFalse(InFlightDetector.isInFlight(result));
        assertTrue(result.getDetail().contains("DONE"));
    }

    @Test
    @DisplayName("IGNORED（200）→ 成功（去重命中），不会被误判成处理中")
    void ignoredBodyIsSuccessAndNotInFlight() {
        responseStatus = 200;
        responseBody = BODY_IGNORED;
        ForwardResult result = relayClient.send(message());

        assertEquals(ForwardOutcome.SUCCESS, result.getOutcome());
        assertFalse(InFlightDetector.isInFlight(result));
        assertTrue(result.getDetail().contains("IGNORED"));
    }

    @Test
    @DisplayName("PERMANENT_FAIL（400）→ 永久失败 + 标记为凭据问题（死信原因记 auth）")
    void permanentFailBodyIsPermanentWithCredentialFlag() {
        responseStatus = 400;
        responseBody = BODY_PERMANENT_FAIL;
        ForwardResult result = relayClient.send(message());

        assertEquals(ForwardOutcome.PERMANENT, result.getOutcome());
        assertTrue(result.isCredentialFailure(), "死信要归到 auth，运维才知道该换令牌而不是重发");
        assertFalse(InFlightDetector.isInFlight(result));
    }

    @Test
    @DisplayName("RETRYABLE（503）→ 可重试，但**不是**处理中（别把普通 503 也写成节流）")
    void retryableBodyIsNotInFlight() {
        responseStatus = 503;
        responseBody = BODY_RETRYABLE;
        ForwardResult result = relayClient.send(message());

        assertEquals(ForwardOutcome.RETRYABLE, result.getOutcome());
        assertFalse(InFlightDetector.isInFlight(result),
                "普通 503 要打 WARN，混成 INFO 会把真故障藏起来");
    }

    @Test
    @DisplayName("精度：note 里出现分开写的 in flight（无下划线）不算命中")
    void looseWordingIsNotInFlight() {
        responseStatus = 503;
        responseBody = "{\"outcome\":\"RETRYABLE\",\"note\":\"失败：处理这条投递时出错：stream in flight aborted\"}";
        ForwardResult result = relayClient.send(message());

        assertFalse(InFlightDetector.isInFlight(result), "只认带下划线的枚举字面量，不做模糊匹配");
    }

    private OutgoingMessage message() {
        return new OutgoingMessage("9f1c2f4e-8a3b-4c5d-9e6f-0a1b2c3d4e5f", "merge_request",
                "application/json;charset=UTF-8", PAYLOAD);
    }

    /** 假下游：永远返回当前设定的状态码与响应体原文，不做任何加工。 */
    private final class FixedResponseHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
            OutputStream output = exchange.getResponseBody();
            try {
                output.write(body);
            } finally {
                output.close();
            }
            exchange.close();
        }
    }
}

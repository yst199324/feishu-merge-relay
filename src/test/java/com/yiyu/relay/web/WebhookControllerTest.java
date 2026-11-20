package com.yiyu.relay.web;

import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.queue.MetaStore;
import com.yiyu.relay.queue.QueueDirectories;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.HeaderNames;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入口校验测试（设计文档 6.1 / 验收清单 C9）。
 *
 * <p>用 standalone MockMvc，不启 Spring 容器：这里要验的是"校验顺序 + 返回码 +
 * 有没有落盘"，跟 bean 装配无关，起容器只会让测试慢十倍。</p>
 */
class WebhookControllerTest {

    private static final String TOKEN = "test-token";
    private static final String DELIVERY_ID = "a1b2c3d4-0001";
    private static final byte[] PAYLOAD = "{\"object_kind\":\"merge_request\"}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private RelayProperties properties;
    private QueueStore queueStore;
    private MockMvc mockMvc;

    /** 挂在入口留档 logger 上的收集器，用来断言"每一条推送都留下了记录"。 */
    private ListAppender<ILoggingEvent> inboundAppender;
    private Logger inboundLogger;

    @BeforeEach
    void setUp() {
        properties = new RelayProperties();
        properties.setWebhookToken(TOKEN);
        properties.setMaxPayloadBytes(64L);
        properties.setQueueDir(tempDir.resolve("queue").toString());
        QueueDirectories directories = new QueueDirectories(properties);
        directories.init();
        queueStore = new QueueStore(directories, new MetaStore(directories));
        mockMvc = MockMvcBuilders.standaloneSetup(new WebhookController(properties, queueStore)).build();

        inboundLogger = (Logger) LoggerFactory.getLogger("com.yiyu.relay.inbound");
        inboundAppender = new ListAppender<>();
        inboundAppender.start();
        inboundLogger.addAppender(inboundAppender);
    }

    @AfterEach
    void tearDown() {
        inboundLogger.detachAppender(inboundAppender);
    }

    @Test
    @DisplayName("缺 X-Codeup-Delivery → 400，不落盘")
    void missingDeliveryIdIsRejected() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, TOKEN)
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isBadRequest());

        assertEquals(0, queueStore.countPending());
        assertEquals(0, queueStore.countRetry());
        assertEquals(0, queueStore.countDead());
    }

    @Test
    @DisplayName("token 不匹配 → 403，不落盘")
    void wrongTokenIsForbidden() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, "wrong-token")
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isForbidden());

        assertEquals(0, queueStore.countPending());
    }

    @Test
    @DisplayName("没带 token → 403")
    void missingTokenIsForbidden() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .content(PAYLOAD))
                .andExpect(status().isForbidden());

        assertEquals(0, queueStore.countPending());
    }

    @Test
    @DisplayName("只带 X-Gitlab-Token 且值正确 → 200 并落盘（云效老约定）")
    void legacyTokenHeaderIsAccepted() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN_LEGACY, TOKEN)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isOk());

        assertEquals(1, queueStore.countPending());
        assertEquals(DELIVERY_ID, queueStore.listPending().get(0).getDeliveryId());
    }

    @Test
    @DisplayName("两个头都带时以 X-Codeup-Token 为准：它错、老头对，仍要 403")
    void primaryTokenWinsOverLegacyWhenBothPresent() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, "wrong-token")
                        .header(HeaderNames.TOKEN_LEGACY, TOKEN)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isForbidden());

        assertEquals(0, queueStore.countPending(), "不能因为老头带对就放行，否则就是后门");
    }

    @Test
    @DisplayName("配置里 token 为空 → 一律拒绝（空密钥等于门没锁）")
    void emptyConfiguredTokenRejectsEverything() throws Exception {
        properties.setWebhookToken("");
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, TOKEN)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .content(PAYLOAD))
                .andExpect(status().isForbidden());

        assertEquals(0, queueStore.countPending());
    }

    @Test
    @DisplayName("正常投递 → 200 且落盘一份，报文原样")
    void acceptedAndStored() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, TOKEN)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .header(HeaderNames.EVENT, "merge_request")
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isOk());

        assertEquals(1, queueStore.countPending());
        QueueFile file = queueStore.listPending().get(0);
        assertEquals(DELIVERY_ID, file.getDeliveryId());
        assertEquals(new String(PAYLOAD, StandardCharsets.UTF_8),
                new String(queueStore.readPayload(file), StandardCharsets.UTF_8));
        assertEquals("merge_request", queueStore.readMeta(DELIVERY_ID).getEventName());
    }

    @Test
    @DisplayName("同一编号重投 → 200，但只落盘一份")
    void duplicateDeliveryIsSkipped() throws Exception {
        sendAccepted();
        sendAccepted();

        assertEquals(1, queueStore.countPending());
    }

    @Test
    @DisplayName("同一编号已在 retry 目录 → 也返回 200 且不再落盘（14.14）")
    void duplicateInRetryDirectoryIsSkipped() throws Exception {
        sendAccepted();
        QueueFile pending = queueStore.listPending().get(0);
        queueStore.moveToRetry(pending, 3, System.currentTimeMillis() + 5000L);

        sendAccepted();

        assertEquals(0, queueStore.countPending(), "retry 里已有同编号，不能再写一份");
        assertEquals(1, queueStore.countRetry());
    }

    @Test
    @DisplayName("报文超过上限 → 413，不落盘")
    void oversizedPayloadIsRejected() throws Exception {
        byte[] big = new byte[200];
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, TOKEN)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(big))
                .andExpect(status().isPayloadTooLarge());

        assertEquals(0, queueStore.countPending());
    }

    @Test
    @DisplayName("编号含目录穿越串 → 清洗后写进 pending 目录，不会逃出去")
    void maliciousDeliveryIdIsSanitized() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, TOKEN)
                        .header(HeaderNames.DELIVERY, "../../etc/passwd")
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isOk());

        assertEquals(1, queueStore.countPending());
        QueueFile file = queueStore.listPending().get(0);
        assertEquals(queueStore.resolveDirectory(QueueStore.DIR_PENDING), file.getPath().getParent());
        assertEquals("------etc-passwd", file.getDeliveryId());
        assertNull(file.getDeadReason());
    }

    @Test
    @DisplayName("验签失败也要留档：请求头 + 报文原文都在，密钥只露前 4 位")
    void forbiddenRequestIsStillLogged() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, "wrong-token")
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isForbidden());

        String logged = onlyInboundLog();
        assertTrue(logged.contains("收到推送"), logged);
        assertTrue(logged.contains("object_kind"), "密钥不对也必须留下报文原文：" + logged);
        assertTrue(logged.contains("X-Codeup-Token=wron*******"), "密钥要留前 4 位再打码：" + logged);
        assertFalse(logged.contains("wrong-token"), "密钥全文不能进日志：" + logged);
    }

    @Test
    @DisplayName("正常入队同样留档：一条推送一条记录，含全部请求头和正文")
    void acceptedRequestIsLogged() throws Exception {
        sendAccepted();

        String logged = onlyInboundLog();
        assertTrue(logged.contains("收到推送"), logged);
        assertTrue(logged.contains("X-Codeup-Delivery=" + DELIVERY_ID), logged);
        assertTrue(logged.contains("bytes=" + PAYLOAD.length), logged);
        assertTrue(logged.contains("object_kind"), logged);
        assertTrue(logged.contains("X-Codeup-Token=test******"), logged);
    }

    @Test
    @DisplayName("报文正文原样入日志：连里面的 token 字段也不打码，否则拿不到原文就没法重放")
    void bodyIsLoggedVerbatim() throws Exception {
        String body = "{\"object_kind\":\"merge_request\",\"access_token\":\"abcd1234\"}";

        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, TOKEN)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        String logged = onlyInboundLog();
        assertTrue(logged.contains("\"access_token\":\"abcd1234\""),
                "正文必须逐字保留（只有请求头才打码）：" + logged);
    }

    @Test
    @DisplayName("密钥仍是 yml 占位值时连占位值本身也不认：忘配 .env 就全部拒绝，而不是放行一个公开默认值")
    void placeholderTokenIsNotAccepted() throws Exception {
        String placeholder = RelayProperties.PLACEHOLDER_MARK + "云效后台的密钥";
        properties.setWebhookToken(placeholder);

        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, placeholder)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isForbidden());

        assertEquals(0, queueStore.countPending(), "占位值不算配过密钥");
    }

    @Test
    @DisplayName("报文超限也留一条记录（写明声明了多少字节）")
    void oversizedRequestIsStillLogged() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, TOKEN)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(new byte[200]))
                .andExpect(status().isPayloadTooLarge());

        String logged = onlyInboundLog();
        assertTrue(logged.contains("超限未读"), logged);
        assertTrue(logged.contains("declaredBytes=200"), logged);
    }

    /** 取本次请求唯一的一条入口留档，顺带守住"一次推送只记一条，不重复"。 */
    private String onlyInboundLog() {
        List<String> messages = inboundAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        assertEquals(1, messages.size(), "一次推送应当且只应当留一条入口记录：" + messages);
        return messages.get(0);
    }

    private void sendAccepted() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, TOKEN)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isOk());
    }
}

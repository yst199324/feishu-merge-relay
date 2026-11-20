package com.yiyu.relay.web;

import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.queue.MetaStore;
import com.yiyu.relay.queue.QueueDirectories;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.HeaderNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private void sendAccepted() throws Exception {
        mockMvc.perform(post(WebhookController.PATH)
                        .header(HeaderNames.TOKEN, TOKEN)
                        .header(HeaderNames.DELIVERY, DELIVERY_ID)
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isOk());
    }
}

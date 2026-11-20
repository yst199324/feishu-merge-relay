package com.yiyu.relay.worker;

import com.yiyu.relay.client.ForwardOutcome;
import com.yiyu.relay.client.ForwardResult;
import com.yiyu.relay.client.OutgoingMessage;
import com.yiyu.relay.client.RelayTarget;
import com.yiyu.relay.queue.MessageMeta;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.RelayStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 转发动作的两条边界：报文文件在扫描后消失、元数据要原样带上。
 */
@ExtendWith(MockitoExtension.class)
class ForwardingServiceTest {

    private static final String DELIVERY_ID = "a1b2c3d4-0001";

    @Mock
    private QueueStore queueStore;

    @Mock
    private RelayTarget relayTarget;

    private ForwardingService service;
    private QueueFile file;

    @BeforeEach
    void setUp() {
        service = new ForwardingService(queueStore, relayTarget, new RelayStats());
        file = QueueFile.pending(Paths.get("queue", "pending", DELIVERY_ID + "_1000.json"), DELIVERY_ID, 1000L);
    }

    @Test
    @DisplayName("文件在扫描之后消失 → 按完成处理，不当失败（否则会把不存在的路径搬进 retry）")
    void vanishedFileCountsAsDone() throws Exception {
        when(queueStore.readPayload(file)).thenThrow(new NoSuchFileException("已被别的线程删掉"));

        ForwardResult result = service.forward(file);

        assertEquals(ForwardOutcome.SUCCESS, result.getOutcome(), result.describe());
        verifyNoInteractions(relayTarget);
    }

    @Test
    @DisplayName("真读不出来（磁盘坏）→ 可重试，文件继续留在队列里")
    void realReadFailureIsRetryable() throws Exception {
        when(queueStore.readPayload(file)).thenThrow(new IOException("Input/output error"));

        assertEquals(ForwardOutcome.RETRYABLE, service.forward(file).getOutcome());
        verifyNoInteractions(relayTarget);
    }

    @Test
    @DisplayName("正常转发：报文原字节 + 事件名 + 原始 Content-Type 一起带过去")
    void forwardsPayloadWithMeta() throws Exception {
        byte[] payload = "{\"object_kind\":\"merge_request\",\"title\":\"修复登录抖动\"}"
                .getBytes(StandardCharsets.UTF_8);
        when(queueStore.readPayload(file)).thenReturn(payload);
        when(queueStore.readMeta(DELIVERY_ID))
                .thenReturn(new MessageMeta("merge_request", "application/json;charset=UTF-8"));
        when(relayTarget.send(any(OutgoingMessage.class))).thenReturn(ForwardResult.success(200));

        assertEquals(ForwardOutcome.SUCCESS, service.forward(file).getOutcome());

        ArgumentCaptor<OutgoingMessage> sent = ArgumentCaptor.forClass(OutgoingMessage.class);
        verify(relayTarget).send(sent.capture());
        assertArrayEquals(payload, sent.getValue().getPayload(), "报文必须原样透传，不能重新序列化");
        assertEquals("merge_request", sent.getValue().getEventName());
        assertEquals("application/json;charset=UTF-8", sent.getValue().getContentType());
    }
}

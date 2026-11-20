package com.yiyu.relay.worker;

import com.yiyu.relay.client.ForwardResult;
import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 待发线程的去留策略：成功删文件、可重试失败进第 1 次重试、永久失败直接进死信。
 */
@ExtendWith(MockitoExtension.class)
class PendingWorkerTest {

    @Mock
    private QueueStore queueStore;

    @Mock
    private ForwardingService forwardingService;

    @Mock
    private QueueOutcomeHandler outcomeHandler;

    private RelayProperties properties;
    private PendingWorker worker;

    @BeforeEach
    void setUp() {
        properties = new RelayProperties();
        worker = new PendingWorker(properties, queueStore, forwardingService, outcomeHandler);
    }

    @Test
    @DisplayName("转发成功 → 删文件")
    void successDeletesFile() {
        QueueFile file = pendingFile();
        stubSinglePending(file);
        when(forwardingService.forward(file)).thenReturn(ForwardResult.success(200));

        assertEquals(1, worker.processRound(0, 1));
        verify(outcomeHandler).deleteAfterSuccess(file);
    }

    @Test
    @DisplayName("第一次失败 → 进入第 1 次重试（等阶梯第一档）")
    void firstFailureSchedulesFirstRetry() {
        QueueFile file = pendingFile();
        stubSinglePending(file);
        when(forwardingService.forward(file)).thenReturn(ForwardResult.transportFailure("连接被拒"));

        worker.processRound(0, 1);

        verify(outcomeHandler).scheduleRetry(file, 1);
    }

    @Test
    @DisplayName("永久失败 → 直接进死信，不在队列里耗着")
    void permanentFailureMovesToDead() {
        QueueFile file = pendingFile();
        stubSinglePending(file);
        ForwardResult permanent = ForwardResult.permanent(400, "凭据失效", true);
        when(forwardingService.forward(file)).thenReturn(permanent);

        worker.processRound(0, 1);

        verify(outcomeHandler).moveToDead(file, permanent);
        verify(outcomeHandler, never()).scheduleRetry(org.mockito.ArgumentMatchers.eq(file),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("每轮最多处理 batch-per-round 条")
    void respectsBatchLimit() {
        QueueFile file = pendingFile();
        when(queueStore.listPending()).thenReturn(Collections.nCopies(
                properties.getPending().getBatchPerRound() + 5, file));
        when(forwardingService.forward(file)).thenReturn(ForwardResult.success(200));

        assertEquals(properties.getPending().getBatchPerRound(), worker.processRound(0, 1));
    }

    private void stubSinglePending(QueueFile file) {
        when(queueStore.listPending()).thenReturn(Collections.singletonList(file));
    }

    private QueueFile pendingFile() {
        return QueueFile.pending(Paths.get("queue", "pending", "id-1_1000.json"), "id-1", 1000L);
    }
}

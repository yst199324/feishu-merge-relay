package com.yiyu.relay.worker;

import com.yiyu.relay.client.ForwardResult;
import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.RetryLadder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 重试线程的调度策略：让路、次数加一、阶梯用尽进死信。
 *
 * <p>依赖倒置的好处在这里体现：worker 依赖的是 {@code ForwardingService} 和
 * {@code QueueOutcomeHandler} 两个协作对象，测试里全部 mock，不用起 HTTP、不用真写文件。</p>
 */
@ExtendWith(MockitoExtension.class)
class RetryWorkerTest {

    @Mock
    private QueueStore queueStore;

    @Mock
    private ForwardingService forwardingService;

    @Mock
    private QueueOutcomeHandler outcomeHandler;

    private RelayProperties properties;
    private RetryLadder retryLadder;
    private RetryWorker worker;

    @BeforeEach
    void setUp() {
        properties = new RelayProperties();
        retryLadder = new RetryLadder(properties);
        worker = new RetryWorker(properties, queueStore, forwardingService, outcomeHandler, retryLadder);
    }

    @Test
    @DisplayName("待发积压达到阈值 → 本轮一条都不重试（让路）")
    void yieldsWhenPendingBacklogIsHigh() {
        when(queueStore.countPending()).thenReturn(properties.getRetry().getMaxPendingBeforeYield());

        int processed = worker.processRound(0, 1);

        assertEquals(0, processed);
        verifyNoInteractions(forwardingService);
    }

    @Test
    @DisplayName("重试成功 → 删文件")
    void successDeletesFile() {
        QueueFile file = retryFile(3);
        stubSingleDue(file);
        when(forwardingService.forward(file)).thenReturn(ForwardResult.success(200));

        assertEquals(1, worker.processRound(0, 1));
        verify(outcomeHandler).deleteAfterSuccess(file);
        verify(outcomeHandler, never()).scheduleRetry(file, 4);
    }

    @Test
    @DisplayName("可重试失败 → 次数加一，继续排队")
    void retryableFailureSchedulesNextAttempt() {
        QueueFile file = retryFile(2);
        stubSingleDue(file);
        when(forwardingService.forward(file)).thenReturn(ForwardResult.retryable(503, "下游 503"));

        worker.processRound(0, 1);

        verify(outcomeHandler).scheduleRetry(file, 3);
    }

    @Test
    @DisplayName("阶梯用尽（第 6 次之后仍失败）→ 进死信，原因 exhausted")
    void exhaustedLadderMovesToDead() {
        QueueFile file = retryFile(retryLadder.maxAttempts());
        stubSingleDue(file);
        when(forwardingService.forward(file)).thenReturn(ForwardResult.transportFailure("连接失败"));

        worker.processRound(0, 1);

        verify(outcomeHandler).moveToDead(eq(file), eq(QueueStore.REASON_EXHAUSTED), anyString());
        verify(outcomeHandler, never()).scheduleRetry(eq(file), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("永久失败（400）→ 直接进死信，不占用剩余重试次数")
    void permanentFailureMovesToDeadImmediately() {
        QueueFile file = retryFile(1);
        stubSingleDue(file);
        when(forwardingService.forward(file)).thenReturn(ForwardResult.permanent(400, "报文解析失败", false));

        worker.processRound(0, 1);

        verify(outcomeHandler).moveToDead(eq(file), org.mockito.ArgumentMatchers.any(ForwardResult.class));
        verify(outcomeHandler, never()).scheduleRetry(eq(file), org.mockito.ArgumentMatchers.anyInt());
    }

    private void stubSingleDue(QueueFile file) {
        when(queueStore.countPending()).thenReturn(0);
        when(queueStore.listRetryDue(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Collections.singletonList(file));
    }

    private QueueFile retryFile(int attempts) {
        return QueueFile.retry(Paths.get("queue", "retry", "id-1_1000_" + attempts + ".json"),
                "id-1", 1000L, attempts);
    }
}

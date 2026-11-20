package com.yiyu.relay.worker;

import com.yiyu.relay.client.ForwardOutcome;
import com.yiyu.relay.client.ForwardResult;
import com.yiyu.relay.client.OutgoingMessage;
import com.yiyu.relay.client.RelayTarget;
import com.yiyu.relay.queue.MessageMeta;
import com.yiyu.relay.queue.QueueFile;
import com.yiyu.relay.queue.QueueStore;
import com.yiyu.relay.support.RelayStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 一次"读报文 → 转发 → 记统计 → 写日志"的完整动作。
 *
 * <p>PendingWorker 和 RetryWorker 都要做这件事，差别只在"失败之后往哪搬"，
 * 所以把公共部分抽到这里，两个 worker 只保留各自的去留策略。</p>
 *
 * <p>日志统一在这里打，理由：转发结论的措辞（尤其是"内部入口没开"这条）
 * 只能有一个出处，否则两处措辞漂移，运维按日志搜关键词就会漏。</p>
 */
@Component
public class ForwardingService {

    private static final Logger log = LoggerFactory.getLogger(ForwardingService.class);

    /** 404/405 时给运维的准确提示（设计文档 14.8：别让人去查网络和防火墙）。 */
    private static final String ENDPOINT_MISSING_HINT =
            "合并项目内部入口没开，请检查 INTERNAL_ENDPOINT=true 以及 relay.target.url 的端口是否正确";

    private final QueueStore queueStore;
    private final RelayTarget relayTarget;
    private final RelayStats relayStats;

    public ForwardingService(QueueStore queueStore, RelayTarget relayTarget, RelayStats relayStats) {
        this.queueStore = queueStore;
        this.relayTarget = relayTarget;
        this.relayStats = relayStats;
    }

    /**
     * 转发一条队列文件。
     *
     * <p>不抛受检异常：读不到报文也要给出一个结论（可重试），
     * 让调用方用同一条路径处理，不会在 worker 里出现第二个 try/catch 分支。</p>
     *
     * @param file 队列文件
     * @return 转发结论
     */
    public ForwardResult forward(QueueFile file) {
        byte[] payload;
        try {
            payload = queueStore.readPayload(file);
        } catch (IOException e) {
            log.error("读取报文失败，本轮按可重试处理：{}（编号 {}，{}）",
                    file.fileName(), file.getDeliveryId(), e.getMessage());
            return ForwardResult.transportFailure("读取本地报文失败: " + e.getMessage());
        }
        MessageMeta meta = queueStore.readMeta(file.getDeliveryId());
        OutgoingMessage message = new OutgoingMessage(
                file.getDeliveryId(), meta.getEventName(), meta.getContentType(), payload);
        ForwardResult result = relayTarget.send(message);
        relayStats.record(result);
        logResult(file, payload.length, result);
        return result;
    }

    private void logResult(QueueFile file, int payloadBytes, ForwardResult result) {
        if (result.getOutcome().isSuccess()) {
            log.info("转发成功：{}（编号 {}，{} 字节，{}）",
                    file.fileName(), file.getDeliveryId(), payloadBytes, result.describe());
            return;
        }
        if (result.getOutcome() == ForwardOutcome.ENDPOINT_MISSING) {
            log.error("{}：{}（编号 {}，{}）",
                    ENDPOINT_MISSING_HINT, file.fileName(), file.getDeliveryId(), result.describe());
            return;
        }
        log.warn("转发失败：{}（编号 {}，{} 字节，{}）",
                file.fileName(), file.getDeliveryId(), payloadBytes, result.describe());
    }
}

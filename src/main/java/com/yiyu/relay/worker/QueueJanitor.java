package com.yiyu.relay.worker;

import com.yiyu.relay.config.RelayProperties;
import com.yiyu.relay.queue.QueueHousekeeping;
import org.springframework.stereotype.Component;

/**
 * 清理线程：每轮执行限额淘汰、超龄淘汰、死信保留期清理、孤儿元数据清扫。
 *
 * <p>为什么单独一个线程而不是塞进消费线程：消费线程的职责是"把消息送出去"，
 * 清理是"把不该留的文件删掉"，混在一起会让超龄判断出现在转发的路径上，
 * 排查时很难说清"这条为什么没了"。单独一个线程也让清理节奏和转发节奏解耦。</p>
 */
@Component
public class QueueJanitor extends AbstractWorker {

    private final QueueHousekeeping housekeeping;

    public QueueJanitor(RelayProperties properties, QueueHousekeeping housekeeping) {
        super(properties, "清理线程", 1);
        this.housekeeping = housekeeping;
    }

    @Override
    protected int processRound(int partitionIndex, int partitionCount) {
        return housekeeping.runAll(System.currentTimeMillis());
    }
}

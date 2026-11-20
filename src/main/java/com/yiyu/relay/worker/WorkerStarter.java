package com.yiyu.relay.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用就绪后启动所有消费线程。
 *
 * <p>为什么不在构造器或 {@code @PostConstruct} 里启动：那时候 Spring 容器还没走完，
 * 万一后面某个 bean 初始化失败，线程已经跑起来、可能已经把消息转发出去了，
 * 进程却要退出 —— 白转发一次。放在 {@link ApplicationReadyEvent} 里，
 * 保证"真的能服务了"才开始消费。</p>
 *
 * <p>每轮的第一轮是立即执行的（初始延迟 0），所以重启后不需要等一个扫描周期
 * 就开始补发积压的消息。</p>
 */
@Component
@Order(30)
public class WorkerStarter {

    private static final Logger log = LoggerFactory.getLogger(WorkerStarter.class);

    private final List<AbstractWorker> workers;

    public WorkerStarter(List<AbstractWorker> workers) {
        this.workers = workers;
    }

    /** 启动全部 worker。 */
    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        if (workers.isEmpty()) {
            log.warn("没有找到任何 worker，队列不会被消费 —— 检查 Spring 组件扫描");
            return;
        }
        for (AbstractWorker worker : workers) {
            worker.start();
        }
        log.info("已启动 {} 组消费线程", workers.size());
    }
}

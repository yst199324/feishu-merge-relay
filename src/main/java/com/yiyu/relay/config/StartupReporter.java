package com.yiyu.relay.config;

import com.yiyu.relay.queue.QueueCounts;
import com.yiyu.relay.queue.QueueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动完成后的自述：队列目录、三个目录条数、转发目标，以及配置约束检查。
 *
 * <p>手册里写的"启动成功标志"就是这里这几行。放在 {@link ApplicationRunner} 里
 * 而不是 {@code @PostConstruct}：此时所有 bean 都已就绪，打出来的条数是
 * "真的开始服务那一刻"的数，不会被后续初始化改动。</p>
 */
@Component
@Order(20)
public class StartupReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReporter.class);

    private final QueueStore queueStore;
    private final RelayProperties properties;
    private final ConfigSanityChecker configSanityChecker;

    public StartupReporter(QueueStore queueStore,
                           RelayProperties properties,
                           ConfigSanityChecker configSanityChecker) {
        this.queueStore = queueStore;
        this.properties = properties;
        this.configSanityChecker = configSanityChecker;
    }

    @Override
    public void run(ApplicationArguments args) {
        QueueCounts counts = queueStore.counts();
        log.info("队列目录就绪：{}", counts.toString());
        log.info("队列路径：{}（最大寿命 {} 分钟，扫描间隔 {} ms）",
                queueStore.getQueueRoot(), properties.getMaxAgeMinutes(), properties.getScanIntervalMs());
        log.info("转发目标：{}（连接超时 {} ms，读超时 {} ms，线程 {}）",
                properties.getTarget().getUrl(),
                properties.getTarget().getConnectTimeoutMs(),
                properties.getTarget().getReadTimeoutMs(),
                properties.getTarget().getThreads());
        configSanityChecker.checkAndLog();
    }
}

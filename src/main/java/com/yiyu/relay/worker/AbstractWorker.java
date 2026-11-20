package com.yiyu.relay.worker;

import com.yiyu.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消费线程的模板：起线程、按固定间隔跑一轮、异常兜底、优雅停止。
 *
 * <p>子类只实现"这一轮做什么"（{@link #processRound(int, int)}），
 * 不用关心线程怎么起、崩了怎么办、关服务时怎么停。</p>
 *
 * <p>两个刻意的设计：</p>
 * <ul>
 *   <li><b>固定延迟</b>（{@code scheduleWithFixedDelay}）而不是固定频率：单条转发最长可能
 *       占满一整个读超时（默认 120 秒），固定频率会堆积任务；固定延迟保证上一轮结束才开始下一轮。</li>
 *   <li><b>启动立刻跑一轮</b>（初始延迟 0）：重启后不用等一个扫描周期才开始补发。</li>
 * </ul>
 *
 * <p>用 {@link DisposableBean} 而不是 {@code @PreDestroy}：继承来的生命周期注解
 * 依赖注解处理器的父类扫描，接口方式更确定。</p>
 */
public abstract class AbstractWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AbstractWorker.class);

    /** 扫描间隔下限。配成 0 或负数会让线程空转，直接吃满 CPU。 */
    private static final long MIN_SCAN_INTERVAL_MS = 200L;

    /**
     * 关闭时等当前这一轮转发结束的时间：比读超时（默认 120 秒）多留 5 秒。
     *
     * <p>注意 stop.sh 只等 35 秒就 SIGKILL，这<b>不丢消息</b>：文件还在 pending，
     * 下次启动会重新转发，合并项目按投递编号去重。它的意义是"运维重启不用等两分钟"，
     * 而这里的 125 秒保证 JVM 自然退出（比如容器里没有强杀）时不至于中断在途请求。</p>
     */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 125L;

    private final RelayProperties properties;
    private final String workerName;
    private final int threadCount;
    private final List<ScheduledExecutorService> executors = new ArrayList<ScheduledExecutorService>();

    private volatile boolean started = false;

    /**
     * @param properties 配置（扫描间隔来自这里）
     * @param workerName 线程名前缀，日志里用来区分是哪个线程
     * @param threadCount 并发分区数，至少 1；大于 1 时不再保证按到达顺序处理
     */
    protected AbstractWorker(RelayProperties properties, String workerName, int threadCount) {
        this.properties = properties;
        this.workerName = workerName;
        this.threadCount = Math.max(1, threadCount);
    }

    /** 启动。重复调用无副作用。 */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        long interval = Math.max(MIN_SCAN_INTERVAL_MS, properties.getScanIntervalMs());
        for (int index = 0; index < threadCount; index++) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                    new NamedThreadFactory(workerName + "-" + (index + 1)));
            executor.scheduleWithFixedDelay(new RoundTask(index), 0L, interval, TimeUnit.MILLISECONDS);
            executors.add(executor);
        }
        log.info("{}已启动：线程数 {}，扫描间隔 {} ms", workerName, threadCount, interval);
    }

    /**
     * 处理一轮。
     *
     * @param partitionIndex 当前分区下标，从 0 开始
     * @param partitionCount 分区总数
     * @return 本轮实际处理了多少条，仅用于日志
     */
    protected abstract int processRound(int partitionIndex, int partitionCount);

    @Override
    public synchronized void destroy() {
        if (!started) {
            return;
        }
        log.info("{}正在停止……", workerName);
        for (ScheduledExecutorService executor : executors) {
            executor.shutdown();
        }
        for (ScheduledExecutorService executor : executors) {
            awaitTermination(executor);
        }
        executors.clear();
        started = false;
        log.info("{}已停止", workerName);
    }

    private void awaitTermination(ScheduledExecutorService executor) {
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{}未能在 {} 秒内结束，强制中断", workerName, SHUTDOWN_TIMEOUT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            // 关服务时被中断是正常情况，恢复中断标记后立即返回
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * 一轮的执行体。
     *
     * <p>这里必须兜住 {@link Throwable}：线程一旦抛出未捕获异常就退出，
     * 之后队列再没人消费 —— 比"这一轮没跑成"严重得多。</p>
     */
    private final class RoundTask implements Runnable {

        private final int partitionIndex;

        private RoundTask(int partitionIndex) {
            this.partitionIndex = partitionIndex;
        }

        @Override
        public void run() {
            try {
                int processed = processRound(partitionIndex, threadCount);
                if (processed > 0) {
                    log.debug("{}第 {} 分区本轮处理 {} 条", workerName, partitionIndex + 1, processed);
                }
            } catch (Throwable t) {
                log.error("{}第 {} 分区本轮处理异常，线程继续存活", workerName, partitionIndex + 1, t);
            }
        }
    }

    /** 给线程起个能看懂的名字，jstack / 日志里一眼知道是谁。 */
    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
            // 守护线程：即使忘了 stop，也不会拖住 JVM 退出
            thread.setDaemon(true);
            return thread;
        }
    }
}

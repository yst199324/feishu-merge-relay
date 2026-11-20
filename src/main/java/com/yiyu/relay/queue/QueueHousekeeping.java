package com.yiyu.relay.queue;

import com.yiyu.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 队列的"清洁工"：限额淘汰、超龄淘汰、死信保留期清理、孤儿元数据清扫。
 *
 * <p>和 {@link QueueStore} 分开的理由：QueueStore 只管"文件怎么放"，这里只管
 * "哪些文件不该留"。消费线程（PendingWorker / RetryWorker）只做转发，
 * 不需要知道限额是怎么回事。</p>
 *
 * <p><b>所有"谁最老"的判断都看文件名里的首次到达毫秒，不看修改时间</b>
 * （retry 的修改时间是未来时刻，按它排序会删掉最快该重试的那条，见设计文档 14.13）。</p>
 */
@Component
public class QueueHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(QueueHousekeeping.class);

    private static final String META_SUFFIX = ".properties";

    private final QueueStore queueStore;
    private final RelayProperties properties;

    public QueueHousekeeping(QueueStore queueStore, RelayProperties properties) {
        this.queueStore = queueStore;
        this.properties = properties;
    }

    /**
     * 跑一轮完整清理。顺序有讲究：先淘汰超龄的，再按条数淘汰，
     * 否则会出现"刚把最老的删掉，其实它本该进死信留档"。
     *
     * @param nowMillis 当前毫秒
     * @return 本轮处理过的文件总数，仅用于日志
     */
    public int runAll(long nowMillis) {
        int expired = expireOverAge(nowMillis);
        int purgedDead = purgeExpiredDead(nowMillis);
        int droppedPending = enforcePendingLimit();
        int droppedRetry = enforceRetryLimit();
        int droppedDead = enforceDeadLimit();
        int orphans = sweepOrphanMetaFiles();
        return expired + purgedDead + droppedPending + droppedRetry + droppedDead + orphans;
    }

    /**
     * 超过总寿命的消息移进死信（原因 expired）。
     *
     * <p>pending 和 retry 共用一个阈值，年龄从首次到达算、搬队列不清零：
     * 最坏也在 3 小时内收口，不会出现"躺了两天，恢复后刷出一堆早就没用的审批卡"。</p>
     *
     * @param nowMillis 当前毫秒
     * @return 移走的条数
     */
    public int expireOverAge(long nowMillis) {
        long deadline = nowMillis - properties.maxAgeMillis();
        int moved = 0;
        moved += expireFrom(QueueStore.DIR_PENDING, queueStore.listPending(), deadline);
        moved += expireFrom(QueueStore.DIR_RETRY, queueStore.listRetry(), deadline);
        return moved;
    }

    private int expireFrom(String directoryName, List<QueueFile> files, long deadline) {
        int moved = 0;
        for (QueueFile file : files) {
            if (file.getArrivedAtMillis() > deadline) {
                continue;
            }
            try {
                QueueFile dead = queueStore.moveToDead(file, QueueStore.REASON_EXPIRED);
                moved++;
                log.warn("消息超龄（{} 分钟），移入死信：{} → {}",
                        properties.getMaxAgeMinutes(), file.fileName(), dead.fileName());
            } catch (IOException e) {
                log.error("消息超龄但移入死信失败，下轮再试：{}/{}（{}）",
                        directoryName, file.fileName(), e.getMessage());
            }
        }
        return moved;
    }

    /**
     * 死信保留期清理：过期直接删文件。
     *
     * <p>为什么必须有：一天只失败一两条的话，500 条上限要攒一年多，
     * 期间报文（含姓名、邮箱）会一直躺在磁盘上。</p>
     *
     * @param nowMillis 当前毫秒
     * @return 删除的条数
     */
    public int purgeExpiredDead(long nowMillis) {
        long deadline = nowMillis - properties.deadRetentionMillis();
        int deleted = 0;
        for (QueueFile file : queueStore.listDead()) {
            if (file.getDeadAtMillis() > deadline) {
                continue;
            }
            deleted += deleteQuietly(file, "死信超过保留期（" + properties.getDead().getMaxAgeDays() + " 天）");
        }
        return deleted;
    }

    /** 待发队列超限：删首次到达最早的一个（不是修改时间最早的）。 */
    public int enforcePendingLimit() {
        return dropOldestBeyond(QueueStore.DIR_PENDING, queueStore.listPending(),
                properties.getPending().getMaxSize());
    }

    /** 重试队列超限：同上。 */
    public int enforceRetryLimit() {
        return dropOldestBeyond(QueueStore.DIR_RETRY, queueStore.listRetry(),
                properties.getRetry().getMaxSize());
    }

    /** 死信超限：删最早进死信的一个。 */
    public int enforceDeadLimit() {
        int allowed = properties.getDead().getMaxSize();
        List<QueueFile> dead = queueStore.listDead();
        if (dead.size() <= allowed) {
            return 0;
        }
        int deleted = 0;
        int overflow = dead.size() - allowed;
        for (int i = 0; i < overflow; i++) {
            deleted += deleteQuietly(dead.get(i), "死信条数超过上限（" + allowed + "）");
        }
        return deleted;
    }

    /**
     * 淘汰超出上限的文件：{@code files} 的顺序不可信，这里自己按<b>首次到达毫秒</b>重排，
     * 保证淘汰的永远是"最早进来的那条"。
     *
     * <p>为什么不能直接用传进来的顺序：{@code retry/} 的列表是按修改时间排的，
     * 而那里的修改时间是"下次该重试的时刻"（未来），按它淘汰会删掉最快该重试的那条，
     * 而不是最老的那条（设计文档 5.4 / 14.13 记录了这个坑）。</p>
     */
    private int dropOldestBeyond(String directoryName, List<QueueFile> files, int maxSize) {
        if (files.size() <= maxSize) {
            return 0;
        }
        List<QueueFile> byArrivalTime = new ArrayList<QueueFile>(files);
        byArrivalTime.sort(Comparator.comparingLong(QueueFile::getArrivedAtMillis));
        int overflow = byArrivalTime.size() - maxSize;
        int dropped = 0;
        for (int i = 0; i < overflow; i++) {
            dropped += deleteQuietly(byArrivalTime.get(i),
                    directoryName + " 条数超过上限（" + maxSize + "）");
        }
        return dropped;
    }

    /**
     * 删除一个文件并记警告日志。
     *
     * <p>丢弃必须记日志：日志里要有编号、文件大小、丢弃原因 ——
     * 没有这行日志，"消息不见了"就永远查不出来（设计文档 5.4）。</p>
     */
    private int deleteQuietly(QueueFile file, String reason) {
        long size = sizeOf(file.getPath());
        try {
            queueStore.delete(file);
            log.warn("丢弃队列文件：{}（编号 {}，{} 字节，原因：{}）",
                    file.fileName(), file.getDeliveryId(), size, reason);
            return 1;
        } catch (IOException e) {
            log.error("丢弃队列文件失败，下轮再试：{}（{}）", file.fileName(), e.getMessage());
            return 0;
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * 扫掉已经没有对应消息的元数据文件。
     *
     * <p>什么时候会产生孤儿：消息被限额丢弃、被手工删掉、或转发成功后删报文却没删干净。
     * 元数据里只有事件名和 Content-Type，不含个人信息，但也没必要留着。</p>
     *
     * @return 清掉的个数
     */
    public int sweepOrphanMetaFiles() {
        Path metaDir = queueStore.getMetaDir();
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(metaDir)) {
            for (Path path : stream) {
                removed += sweepOneMeta(path);
            }
        } catch (IOException e) {
            log.warn("扫描元数据目录失败：{}（{}）", metaDir, e.getMessage());
        }
        return removed;
    }

    private int sweepOneMeta(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(META_SUFFIX)) {
            return 0;
        }
        String deliveryId = fileName.substring(0, fileName.length() - META_SUFFIX.length());
        if (queueStore.existsByDeliveryId(deliveryId)) {
            return 0;
        }
        try {
            Files.deleteIfExists(path);
            log.debug("清理孤儿元数据：{}", fileName);
            return 1;
        } catch (IOException e) {
            log.warn("清理孤儿元数据失败：{}（{}）", fileName, e.getMessage());
            return 0;
        }
    }
}

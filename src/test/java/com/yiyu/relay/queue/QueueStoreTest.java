package com.yiyu.relay.queue;

import com.yiyu.relay.config.RelayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列文件名规则与清理策略的单元测试。
 *
 * <p>重点验证设计文档里最反直觉的两条：</p>
 * <ul>
 *   <li>"谁最老"看文件名里的首次到达毫秒，<b>不看修改时间</b>（14.13）；</li>
 *   <li>编号必须清洗，防目录穿越（14.3）。</li>
 * </ul>
 */
class QueueStoreTest {

    private static final byte[] PAYLOAD = "{\"object_kind\":\"merge_request\"}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private RelayProperties properties;
    private QueueStore queueStore;
    private QueueHousekeeping housekeeping;

    @BeforeEach
    void setUp() {
        properties = new RelayProperties();
        properties.setQueueDir(tempDir.resolve("queue").toString());
        // 测试里把上限调小，方便构造"超限"场景
        properties.getPending().setMaxSize(2);
        properties.getRetry().setMaxSize(2);
        properties.getDead().setMaxSize(2);
        QueueDirectories directories = new QueueDirectories(properties);
        directories.init();
        // 手工装配：QueueStore 只依赖目录与元数据两个协作对象，测试里不需要起 Spring 容器
        queueStore = new QueueStore(directories, new MetaStore(directories));
        housekeeping = new QueueHousekeeping(queueStore, properties);
    }

    @Test
    @DisplayName("pending 文件名 = 编号_首次到达毫秒.json")
    void pendingFileName() throws IOException {
        QueueFile file = queueStore.enqueue(message("a1b2c3d4-0001", 1763604612000L));
        assertEquals("a1b2c3d4-0001_1763604612000.json", file.fileName());
        assertEquals("a1b2c3d4-0001", file.getDeliveryId());
        assertEquals(1763604612000L, file.getArrivedAtMillis());
        assertEquals(0, file.getAttempts());
        assertTrue(Files.exists(queueStore.resolveDirectory(QueueStore.DIR_PENDING).resolve(file.fileName())));
    }

    @Test
    @DisplayName("retry 文件名 = 编号_首次到达毫秒_已试次数.json")
    void retryFileName() throws IOException {
        QueueFile retry = queueStore.moveToRetry(queueStore.enqueue(message("abc", 1763604612000L)), 3,
                1763604700000L);
        assertEquals("abc_1763604612000_3.json", retry.fileName());
        assertEquals(3, retry.getAttempts());
        // 首次到达毫秒跨目录不变：重试不清零
        assertEquals(1763604612000L, retry.getArrivedAtMillis());
    }

    @Test
    @DisplayName("dead 文件名 = 编号_首次到达毫秒_进死信时间_原因.json")
    void deadFileName() throws IOException {
        QueueFile dead = queueStore.moveToDead(queueStore.enqueue(message("abc", 1763604612000L)),
                QueueStore.REASON_AUTH);
        assertTrue(dead.fileName().matches("abc_1763604612000_\\d{14}_auth\\.json"), dead.fileName());
        assertEquals(QueueStore.REASON_AUTH, dead.getDeadReason());
        assertTrue(dead.getDeadAtMillis() > 0L);
        // 移入死信时修改时间必须重置成"现在"，不能把 retry 的未来时间带过来
        long modified = Files.getLastModifiedTime(dead.getPath()).toMillis();
        assertTrue(Math.abs(System.currentTimeMillis() - modified) < 60_000L);
    }

    @Test
    @DisplayName("同一编号在 pending / retry / dead 任一处都算重复")
    void duplicateCheckSpansAllThreeDirectories() throws IOException {
        QueueFile file = queueStore.enqueue(message("dup-1", System.currentTimeMillis()));
        assertTrue(queueStore.existsByDeliveryId("dup-1"));
        assertFalse(queueStore.existsByDeliveryId("dup-2"));

        queueStore.moveToRetry(file, 1, System.currentTimeMillis() + 5000L);
        assertTrue(queueStore.existsByDeliveryId("dup-1"), "移进 retry 后仍要能查到");

        QueueFile retry = queueStore.listRetry().get(0);
        queueStore.moveToDead(retry, QueueStore.REASON_EXHAUSTED);
        assertTrue(queueStore.existsByDeliveryId("dup-1"), "进死信的同编号按已放弃处理，也不复活");
    }

    @Test
    @DisplayName("同编号并发入队只落一份文件（锁内复查关掉竞态窗口）")
    void concurrentSameDeliveryEnqueuesOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<QueueFile>> futures = new ArrayList<Future<QueueFile>>();
        try {
            for (int i = 0; i < threads; i++) {
                // 每条给不同的到达毫秒，模拟"查重时谁都没看见对方"的真实竞态
                final long arrivedAt = 1000L + i;
                futures.add(pool.submit(new Callable<QueueFile>() {
                    @Override
                    public QueueFile call() throws Exception {
                        start.await();
                        return queueStore.enqueue(message("race-1", arrivedAt));
                    }
                }));
            }
            start.countDown();

            Path firstPath = null;
            for (Future<QueueFile> future : futures) {
                Path path = future.get(10, TimeUnit.SECONDS).getPath();
                if (firstPath == null) {
                    firstPath = path;
                }
                assertEquals(firstPath, path, "并发的同一条投递必须都指向同一个文件");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, queueStore.countPending(), "同一条投递在队列里只能有一份");
    }

    @Test
    @DisplayName("pending 超限删首次到达最早的那条")
    void pendingLimitDropsOldestByArrivalTime() throws IOException {
        long now = System.currentTimeMillis();
        queueStore.enqueue(message("m1", now - 3000L));
        queueStore.enqueue(message("m2", now - 2000L));
        queueStore.enqueue(message("m3", now - 1000L));

        assertEquals(1, housekeeping.enforcePendingLimit());
        List<String> remaining = deliveryIds(queueStore.listPending());
        assertEquals(2, remaining.size());
        assertFalse(remaining.contains("m1"), "最早的 m1 应被丢弃");
        assertTrue(remaining.contains("m2"));
        assertTrue(remaining.contains("m3"));
    }

    @Test
    @DisplayName("retry 超限按首次到达毫秒判定，不看（未来的）修改时间")
    void retryLimitIgnoresModifiedTime() throws IOException {
        long now = System.currentTimeMillis();
        QueueFile m1 = queueStore.enqueue(message("m1", now - 3000L));
        QueueFile m2 = queueStore.enqueue(message("m2", now - 2000L));
        QueueFile m3 = queueStore.enqueue(message("m3", now - 1000L));
        // 故意让 m1（最老）的下次重试时刻最晚，m3（最新）的最早：
        // 若谁按修改时间删，就会删掉 m1 以外的错的那条
        queueStore.moveToRetry(m1, 1, now + 30_000L);
        queueStore.moveToRetry(m2, 1, now + 20_000L);
        queueStore.moveToRetry(m3, 1, now + 10_000L);

        assertEquals(1, housekeeping.enforceRetryLimit());
        List<String> remaining = deliveryIds(queueStore.listRetry());
        assertEquals(2, remaining.size());
        assertFalse(remaining.contains("m1"), "应按首次到达时间丢弃 m1");
        assertTrue(remaining.contains("m2"));
        assertTrue(remaining.contains("m3"));
    }

    @Test
    @DisplayName("pending 里超过总寿命的消息移入死信，原因 expired")
    void expireOverAgeMovesPendingToDead() throws IOException {
        long now = System.currentTimeMillis();
        queueStore.enqueue(message("old", now - properties.maxAgeMillis() - 60_000L));
        queueStore.enqueue(message("fresh", now));

        assertEquals(1, housekeeping.expireOverAge(now));
        List<String> pending = deliveryIds(queueStore.listPending());
        assertEquals(1, pending.size());
        assertTrue(pending.contains("fresh"));

        List<QueueFile> dead = queueStore.listDead();
        assertEquals(1, dead.size());
        assertEquals("old", dead.get(0).getDeliveryId());
        assertEquals(QueueStore.REASON_EXPIRED, dead.get(0).getDeadReason());
    }

    @Test
    @DisplayName("retry 里已超龄的消息即使到期了也直接进死信，不会被转发")
    void expireOverAgeAppliesToRetryEvenWhenModifiedTimeIsFuture() throws IOException {
        long now = System.currentTimeMillis();
        QueueFile file = queueStore.enqueue(message("stale", now - properties.maxAgeMillis() - 60_000L));
        // 修改时间设成"1 分钟后到期"：它不该被当成一条正常的待重试消息
        queueStore.moveToRetry(file, 2, now + 60_000L);

        assertEquals(1, housekeeping.expireOverAge(now));
        assertTrue(queueStore.listRetry().isEmpty());
        assertEquals(1, queueStore.listDead().size());
    }

    @Test
    @DisplayName("死信超过保留期直接删文件")
    void purgeExpiredDead() throws IOException {
        long now = System.currentTimeMillis();
        long oldDeadAt = now - properties.deadRetentionMillis() - 24L * 3600L * 1000L;
        Path deadDir = queueStore.resolveDirectory(QueueStore.DIR_DEAD);
        Path oldFile = deadDir.resolve(QueueFileName.dead("old", now - 10L * 24L * 3600L * 1000L,
                oldDeadAt, QueueStore.REASON_EXPIRED));
        Files.write(oldFile, PAYLOAD);
        Path freshFile = deadDir.resolve(QueueFileName.dead("new", now, now, QueueStore.REASON_BAD));
        Files.write(freshFile, PAYLOAD);

        assertEquals(1, housekeeping.purgeExpiredDead(now));
        assertEquals(1, queueStore.listDead().size());
        assertEquals("new", queueStore.listDead().get(0).getDeliveryId());
    }

    @Test
    @DisplayName("投递编号清洗：目录穿越串被替换，长度截到 64")
    void deliveryIdSanitizingBlocksPathTraversal() throws IOException {
        String malicious = "../../etc/passwd";
        String cleaned = com.yiyu.relay.support.DeliveryIds.sanitize(malicious);
        assertEquals("------etc-passwd", cleaned);
        assertFalse(cleaned.contains("/"));

        QueueFile file = queueStore.enqueue(message(cleaned, System.currentTimeMillis()));
        assertEquals(queueStore.resolveDirectory(QueueStore.DIR_PENDING), file.getPath().getParent());

        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longId.append('x');
        }
        assertEquals(64, com.yiyu.relay.support.DeliveryIds.sanitize(longId.toString()).length());
    }

    @Test
    @DisplayName("文件名解析：不合规的文件被跳过，不影响整轮扫描")
    void malformedFileNamesAreSkipped() throws IOException {
        assertFalse(QueueFileName.belongsTo(QueueStore.DIR_PENDING, "not-a-queue-file.json", "abc"));
        Path pendingDir = queueStore.resolveDirectory(QueueStore.DIR_PENDING);
        Files.write(pendingDir.resolve("readme.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        Files.write(pendingDir.resolve("abc_notanumber.json"), PAYLOAD);
        Files.write(pendingDir.resolve(".abc_1.json.tmp"), PAYLOAD);

        assertTrue(queueStore.listPending().isEmpty(), "非法文件名不应被当成消息");
    }

    @Test
    @DisplayName("查重按编号全等比较：abc 不会把 abc_x 的文件认成自己的")
    void belongsToComparesWholeId() {
        long now = System.currentTimeMillis();
        String otherFile = QueueFileName.pending("abc_x", now);

        // 这一条是回归守卫：原来用 startsWith("abc_") 判断，abc 会把 abc_x 认成自己，
        // 后到的消息就被当成"重复投递"回 200 不落盘 —— 静默丢消息
        assertFalse(QueueFileName.belongsTo(QueueStore.DIR_PENDING, otherFile, "abc"),
                "前缀相同的两个编号不是同一条消息");
        assertTrue(QueueFileName.belongsTo(QueueStore.DIR_PENDING, otherFile, "abc_x"));

        // 编号本身带下划线时，三种队列的文件名都要能切出正确编号
        assertTrue(QueueFileName.belongsTo(QueueStore.DIR_RETRY,
                QueueFileName.retry("a_b_1", now, 2), "a_b_1"));
        assertTrue(QueueFileName.belongsTo(QueueStore.DIR_DEAD,
                QueueFileName.dead("a_b_1", now, now, QueueStore.REASON_BAD), "a_b_1"));
        assertFalse(QueueFileName.belongsTo(QueueStore.DIR_DEAD,
                QueueFileName.dead("a_b_1", now, now, QueueStore.REASON_BAD), "a_b"));

        // 含 Windows 非法字符的文件名：认不出来就当不是，绝不能把整轮扫描打断
        assertFalse(QueueFileName.belongsTo(QueueStore.DIR_PENDING, "a:b.json", "a:b"));
    }

    @Test
    @DisplayName("元数据随消息保存，成功删文件时一并清理")
    void metaStoredAndRemovedWithMessage() throws IOException {
        long now = System.currentTimeMillis();
        QueueFile file = queueStore.enqueue(new InboundMessage("meta-1", "merge_request",
                "application/json;charset=UTF-8", PAYLOAD, now));
        MessageMeta meta = queueStore.readMeta("meta-1");
        assertEquals("merge_request", meta.getEventName());
        assertEquals("application/json;charset=UTF-8", meta.getContentType());
        assertNotNull(meta);

        queueStore.delete(file);
        assertNull(queueStore.readMeta("meta-1").getEventName());
        assertEquals(0, housekeeping.sweepOrphanMetaFiles());
    }

    private InboundMessage message(String deliveryId, long arrivedAtMillis) {
        return new InboundMessage(deliveryId, "merge_request", "application/json;charset=UTF-8",
                PAYLOAD, arrivedAtMillis);
    }

    private List<String> deliveryIds(List<QueueFile> files) {
        List<String> ids = new ArrayList<String>();
        for (QueueFile file : files) {
            ids.add(file.getDeliveryId());
        }
        return ids;
    }
}

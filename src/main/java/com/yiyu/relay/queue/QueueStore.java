package com.yiyu.relay.queue;

import com.yiyu.relay.support.DeliveryIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 队列的对外门面：入队、列举、改名换目录、删除、计数。
 *
 * <p>职责边界很硬：<b>只回答"文件在哪、怎么搬"，不回答"该不该搬"</b>。
 * 谁该重试、谁该进死信由 worker 决定；谁该被限额淘汰由 {@link QueueHousekeeping} 决定。
 * 目录结构在 {@link QueueDirectories}，请求头元数据在 {@link MetaStore}。</p>
 *
 * <p><b>状态全在目录和文件名里</b>，没有任何内存态：进程重启后第一轮扫描就能看到
 * 磁盘上剩下的消息，自动补发，不需要恢复流程（设计文档 5.3）。</p>
 */
@Component
public class QueueStore {

    private static final Logger log = LoggerFactory.getLogger(QueueStore.class);

    /** 待发目录。 */
    public static final String DIR_PENDING = "pending";
    /** 重试目录。 */
    public static final String DIR_RETRY = "retry";
    /** 死信目录。 */
    public static final String DIR_DEAD = "dead";

    /**
     * 元数据目录（编号 → 事件名 + Content-Type）。
     *
     * <p>这是对设计文档 5.1 的唯一一处补充：那三个目录只存报文原文，
     * 而转发时必须原样带回 Content-Type（含 charset）和事件名。</p>
     */
    public static final String DIR_META = "meta";

    /** 死信原因：云效凭据失效。 */
    public static final String REASON_AUTH = "auth";
    /** 死信原因：报文或参数有问题，重试无用。 */
    public static final String REASON_BAD = "bad";
    /** 死信原因：重试阶梯用尽。 */
    public static final String REASON_EXHAUSTED = "exhausted";
    /** 死信原因：在队列里超过总寿命。 */
    public static final String REASON_EXPIRED = "expired";

    private static final String TMP_SUFFIX = ".tmp";

    private final QueueDirectories directories;
    private final MetaStore metaStore;

    /** 入队期间持有的锁，见 {@link #enqueue} 里的说明。 */
    private final Object enqueueLock = new Object();

    public QueueStore(QueueDirectories directories, MetaStore metaStore) {
        this.directories = directories;
        this.metaStore = metaStore;
    }

    /** 启动时确认目录可用（目录本身由 {@link QueueDirectories} 建立）。 */
    @PostConstruct
    public void init() {
        directories.init();
    }

    /**
     * 把一个消息落盘到待发目录。
     *
     * <p>整个过程串行化。原因：入口的"查重"和"写文件"是两步，云效在几毫秒内重投同一份报文时，
     * 两个请求可能都判成"没见过"，然后各写一份——文件名里的到达毫秒不同，于是两条并存、
     * 分别处理、发两张卡。<b>锁内再查一次</b>才能把这个窗口关掉。</p>
     *
     * <p>锁只在入队这一瞬间持有，写的是一个小文件；一天几十条消息，代价可以忽略。
     * 换来的是"同一条投递在队列里永远只有一份"，不用再靠合并项目那边 30 分钟去重兜底。</p>
     *
     * @param message 消息
     * @return 落盘后的队列文件；同编号已在队列里则返回已有的那个（调用方视为重复投递，不重复发卡）
     * @throws IOException 写文件失败（磁盘满、权限错）—— 调用方必须返回 500，不能假装成功
     */
    public QueueFile enqueue(InboundMessage message) throws IOException {
        synchronized (enqueueLock) {
            QueueFile existing = findExisting(message.getDeliveryId());
            if (existing != null) {
                log.info("同编号已在队列里，不重复落盘：{}（编号 {}）", existing.fileName(), message.getDeliveryId());
                return existing;
            }
            Path target = directories.resolve(DIR_PENDING).resolve(
                    QueueFileName.pending(message.getDeliveryId(), message.getArrivedAtMillis()));
            writeAtomically(target, message.getPayload());
            metaStore.write(message.getDeliveryId(),
                    new MessageMeta(message.getEventName(), message.getContentType()));
            return QueueFileName.parse(DIR_PENDING, target);
        }
    }

    private QueueFile findExisting(String deliveryId) {
        return DeliveryIds.isBlank(deliveryId) ? null : directories.findByDeliveryId(deliveryId);
    }

    /**
     * 先写临时文件再原子改名。
     *
     * <p>为什么不能直接写目标文件：写入中途崩溃会留下半截文件，下一轮扫描
     * 会把它当合法报文转发出去。临时文件名以 {@code .} 开头、以 {@code .tmp} 结尾，
     * 列举时会被过滤掉，不会混进队列。</p>
     */
    private void writeAtomically(Path target, byte[] payload) throws IOException {
        Path temp = target.resolveSibling("." + target.getFileName() + TMP_SUFFIX);
        byte[] safePayload = payload == null ? new byte[0] : payload;
        Files.write(temp, safePayload,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        FileAttributes.restrictFile(temp);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("文件系统不支持原子改名，退化为普通改名：{}", target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 读报文原文。
     *
     * @param file 队列文件
     * @return 报文字节
     * @throws IOException 文件读不到（已被别的线程删掉等）
     */
    public byte[] readPayload(QueueFile file) throws IOException {
        return Files.readAllBytes(file.getPath());
    }

    /**
     * 读元数据。缺失或损坏时返回 {@link MessageMeta#EMPTY}，
     * 让转发继续（只是 Content-Type 回落默认值），不因为一份辅助文件而丢消息。
     *
     * @param deliveryId 投递编号
     * @return 元数据，不会为 null
     */
    public MessageMeta readMeta(String deliveryId) {
        return metaStore.read(deliveryId);
    }

    /** 待发文件列表，按首次到达毫秒从早到晚。 */
    public List<QueueFile> listPending() {
        return directories.listByArrivalTime(DIR_PENDING);
    }

    /** 重试文件列表，按"下次该重试的时刻"从早到晚。 */
    public List<QueueFile> listRetry() {
        return directories.listRetryByReadyTime();
    }

    /**
     * 重试目录里已经到期的文件，按到期时间从早到晚。
     *
     * <p>到期判断只看修改时间（retry 里它的语义就是"什么时候可以动它"），
     * 与"这条进来多久了"完全分开。</p>
     *
     * @param nowMillis 当前毫秒
     * @return 到期文件
     */
    public List<QueueFile> listRetryDue(long nowMillis) {
        return directories.listRetryDue(nowMillis);
    }

    /** 死信文件列表，按进死信时间从早到晚（保留期清理用）。 */
    public List<QueueFile> listDead() {
        return directories.listDeadByDeadTime();
    }

    /**
     * 入队查重：pending、retry、dead 三个目录都看同编号（设计文档 5.2 / 14.14）。
     *
     * <p>只查 pending 不够 —— 文件一旦失败移进 retry，云效这时重投同一份报文就会
     * 又写一份，两条并存分别处理，发两张卡。dead 里的同编号按"已放弃"处理，也不复活。</p>
     *
     * @param deliveryId 已清洗的投递编号
     * @return true 表示这条消息已经在队列里（或已放弃）
     */
    public boolean existsByDeliveryId(String deliveryId) {
        return findExisting(deliveryId) != null;
    }

    /**
     * 移进重试目录：次数 +1、修改时间改成下次该重试的时刻。
     *
     * @param file              源文件
     * @param attempts          新的已试次数
     * @param nextRetryAtMillis 下次该重试的时刻（毫秒）
     * @return 重试文件
     * @throws IOException 改名失败
     */
    public QueueFile moveToRetry(QueueFile file, int attempts, long nextRetryAtMillis) throws IOException {
        Path target = directories.resolve(DIR_RETRY).resolve(
                QueueFileName.retry(file.getDeliveryId(), file.getArrivedAtMillis(), attempts));
        Files.move(file.getPath(), target, StandardCopyOption.REPLACE_EXISTING);
        FileAttributes.setModifiedTime(target, nextRetryAtMillis);
        return QueueFileName.parse(DIR_RETRY, target);
    }

    /**
     * 移进死信目录，并把修改时间重置成当前时间。
     *
     * <p>必须重置：从 retry 里带过来的修改时间是"下次该重试的时刻"，是个未来时间，
     * 死信目录的限额清理若按它排序会删错文件（设计文档 5.3）。</p>
     *
     * @param file   源文件
     * @param reason 原因标记（auth / bad / exhausted / expired）
     * @return 死信文件
     * @throws IOException 改名失败
     */
    public QueueFile moveToDead(QueueFile file, String reason) throws IOException {
        long now = System.currentTimeMillis();
        Path target = directories.resolve(DIR_DEAD).resolve(
                QueueFileName.dead(file.getDeliveryId(), file.getArrivedAtMillis(), now, reason));
        Files.move(file.getPath(), target, StandardCopyOption.REPLACE_EXISTING);
        FileAttributes.setModifiedTime(target, now);
        return QueueFileName.parse(DIR_DEAD, target);
    }

    /**
     * 删掉文件（成功转发后的收尾），连同元数据一起删。
     *
     * @param file 要删的文件
     * @throws IOException 删除失败
     */
    public void delete(QueueFile file) throws IOException {
        Files.deleteIfExists(file.getPath());
        metaStore.delete(file.getDeliveryId());
    }

    public int countPending() {
        return directories.list(DIR_PENDING).size();
    }

    public int countRetry() {
        return directories.list(DIR_RETRY).size();
    }

    public int countDead() {
        return directories.list(DIR_DEAD).size();
    }

    /** 三个目录条数。 */
    public QueueCounts counts() {
        return new QueueCounts(countPending(), countRetry(), countDead());
    }

    /** 待发队列里最老一条的首次到达毫秒，空队列返回 0。 */
    public long oldestPendingArrivedAtMillis() {
        return firstArrivedAtMillis(DIR_PENDING);
    }

    /** 重试队列里最老一条的首次到达毫秒，空队列返回 0。 */
    public long oldestRetryArrivedAtMillis() {
        return firstArrivedAtMillis(DIR_RETRY);
    }

    private long firstArrivedAtMillis(String directoryName) {
        long oldest = 0L;
        for (QueueFile file : directories.list(directoryName)) {
            oldest = mergeOldest(oldest, file.getArrivedAtMillis());
        }
        return oldest;
    }

    private long mergeOldest(long current, long candidate) {
        if (current <= 0L) {
            return candidate;
        }
        return Math.min(current, candidate);
    }

    /** 队列根目录，日志和 /health 展示用。 */
    public Path getQueueRoot() {
        return directories.root();
    }

    /** 元数据目录，清理孤儿文件用。 */
    public Path getMetaDir() {
        return metaStore.directory();
    }

    /** 目录名 → 路径，测试与清理逻辑用。 */
    public Path resolveDirectory(String directoryName) {
        return directories.resolve(directoryName);
    }
}

package com.yiyu.relay.queue;

import com.yiyu.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 队列目录：路径解析、目录建立、列举与排序。
 *
 * <p>把"目录长什么样"和"消息怎么进出"拆开，是为了让 {@link QueueStore} 保持只讲业务动作。
 * 这里只做文件系统层面的事：知道三个目录在哪、有哪些文件、按什么顺序。</p>
 *
 * <p>排序规则集中在这里，是因为它最容易搞混：</p>
 * <ul>
 *   <li>待发：按<b>首次到达毫秒</b>（= pending 的修改时间）从早到晚，保证保序处理；</li>
 *   <li>重试：按<b>修改时间</b>（= 下次该重试的时刻）从早到晚，只用来判断"到期没到期"；</li>
 *   <li>死信：按文件名里的<b>进死信时间</b>从早到晚，保留期清理用。</li>
 * </ul>
 */
@Component
public class QueueDirectories {

    private static final Logger log = LoggerFactory.getLogger(QueueDirectories.class);

    private final RelayProperties properties;

    private Path root;
    private Path pending;
    private Path retry;
    private Path dead;
    private Path meta;

    public QueueDirectories(RelayProperties properties) {
        this.properties = properties;
    }

    /**
     * 建立目录并设权限（目录 700）。
     *
     * <p>失败直接抛出去让应用起不来：没地方写文件的服务收下消息就是丢消息，
     * 与其假装正常，不如在启动阶段就暴露。</p>
     */
    @PostConstruct
    public void init() {
        root = Paths.get(properties.getQueueDir()).toAbsolutePath().normalize();
        pending = root.resolve(QueueStore.DIR_PENDING);
        retry = root.resolve(QueueStore.DIR_RETRY);
        dead = root.resolve(QueueStore.DIR_DEAD);
        meta = root.resolve(QueueStore.DIR_META);
        try {
            createDirectory(root);
            createDirectory(pending);
            createDirectory(retry);
            createDirectory(dead);
            createDirectory(meta);
        } catch (IOException e) {
            throw new IllegalStateException("队列目录创建失败：" + root, e);
        }
        log.info("队列目录已建立：{}", root);
    }

    private void createDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        FileAttributes.restrictDirectory(directory);
    }

    /** 队列根目录。 */
    public Path root() {
        return root;
    }

    /** 元数据目录。 */
    public Path metaDirectory() {
        return meta;
    }

    /**
     * 目录名 → 路径。
     *
     * @param directoryName pending / retry / dead / meta
     * @return 绝对路径
     */
    public Path resolve(String directoryName) {
        if (QueueStore.DIR_PENDING.equals(directoryName)) {
            return pending;
        }
        if (QueueStore.DIR_RETRY.equals(directoryName)) {
            return retry;
        }
        if (QueueStore.DIR_DEAD.equals(directoryName)) {
            return dead;
        }
        if (QueueStore.DIR_META.equals(directoryName)) {
            return meta;
        }
        throw new IllegalArgumentException("未知的队列目录：" + directoryName);
    }

    /** 列目录里所有能解析的文件（手工丢进来的怪文件直接跳过，不影响整轮扫描）。 */
    public List<QueueFile> list(String directoryName) {
        List<QueueFile> files = new ArrayList<QueueFile>();
        Path directory = resolve(directoryName);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                QueueFile file = QueueFileName.parse(directoryName, path);
                if (file != null) {
                    files.add(file);
                }
            }
        } catch (IOException e) {
            log.warn("列举目录失败：{}（{}），本轮按空目录处理", directory, e.getMessage());
        }
        return files;
    }

    /**
     * 待发文件：按首次到达毫秒从早到晚。
     *
     * <p>pending 里的修改时间就是到达时间，两者等价；这里用文件名里的值，避免每次
     * 排序都去 stat 一遍文件（设计文档 5.7 要求保序，顺序由这里保证）。</p>
     */
    public List<QueueFile> listByArrivalTime(String directoryName) {
        List<QueueFile> files = list(directoryName);
        files.sort(Comparator.comparingLong(QueueFile::getArrivedAtMillis));
        return files;
    }

    /** 死信文件：按文件名里的进死信时间从早到晚。 */
    public List<QueueFile> listDeadByDeadTime() {
        List<QueueFile> files = list(QueueStore.DIR_DEAD);
        files.sort(Comparator.comparingLong(QueueFile::getDeadAtMillis));
        return files;
    }

    /** 重试文件：按修改时间（下次该重试的时刻）从早到晚。 */
    public List<QueueFile> listRetryByReadyTime() {
        return toQueueFiles(listRetryTimed());
    }

    /**
     * 重试目录里已经到期的文件，按到期时间从早到晚。
     *
     * @param nowMillis 当前毫秒
     * @return 到期文件
     */
    public List<QueueFile> listRetryDue(long nowMillis) {
        List<QueueFile> due = new ArrayList<QueueFile>();
        for (TimedFile timed : listRetryTimed()) {
            if (timed.readyAtMillis <= nowMillis) {
                due.add(timed.file);
            }
        }
        return due;
    }

    /**
     * 三个目录里找同编号的文件（入队查重用，只比文件名不读内容）。
     *
     * <p>顺序是 pending → retry → dead，任何一个命中就返回：编号唯一，
     * 同一条消息不可能同时躺在两个目录里。</p>
     *
     * @param deliveryId 已清洗的投递编号
     * @return 找到的文件；三个目录都没有返回 null
     */
    public QueueFile findByDeliveryId(String deliveryId) {
        for (String directoryName : new String[]{QueueStore.DIR_PENDING, QueueStore.DIR_RETRY, QueueStore.DIR_DEAD}) {
            QueueFile found = findIn(directoryName, deliveryId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private QueueFile findIn(String directoryName, String deliveryId) {
        Path directory = resolve(directoryName);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (QueueFileName.belongsTo(path.getFileName().toString(), deliveryId)) {
                    return QueueFileName.parse(directoryName, path);
                }
            }
        } catch (IOException e) {
            log.warn("扫描目录失败，本次查重按'未见过'处理：{}（{}）", directory, e.getMessage());
        }
        return null;
    }

    private List<TimedFile> listRetryTimed() {
        List<TimedFile> timed = new ArrayList<TimedFile>();
        for (QueueFile file : list(QueueStore.DIR_RETRY)) {
            timed.add(new TimedFile(file, FileAttributes.modifiedTime(file.getPath())));
        }
        timed.sort(Comparator.comparingLong(TimedFile::getReadyAtMillis));
        return timed;
    }

    private List<QueueFile> toQueueFiles(List<TimedFile> timed) {
        List<QueueFile> files = new ArrayList<QueueFile>(timed.size());
        for (TimedFile entry : timed) {
            files.add(entry.getFile());
        }
        return files;
    }

    /** 文件 + 它的"可以动它的时刻"（retry 里是修改时间）。 */
    private static final class TimedFile {

        private final QueueFile file;
        private final long readyAtMillis;

        private TimedFile(QueueFile file, long readyAtMillis) {
            this.file = file;
            this.readyAtMillis = readyAtMillis;
        }

        private QueueFile getFile() {
            return file;
        }

        private long getReadyAtMillis() {
            return readyAtMillis;
        }
    }
}

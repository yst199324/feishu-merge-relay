package com.yiyu.relay.queue;

import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 三个队列的文件名规则（设计文档 5.2）。
 *
 * <pre>
 * pending/  {投递编号}_{首次到达毫秒}.json                       a1b2c3d4-0001_1763604612000.json
 * retry/    {投递编号}_{首次到达毫秒}_{已试次数}.json              a1b2c3d4-0001_1763604612000_3.json
 * dead/     {投递编号}_{首次到达毫秒}_{进死信时间}_{原因}.json      a1b2c3d4-0001_1763604612000_20261120093012_auth.json
 * </pre>
 *
 * <p>为什么编号放在最前面、后面几段都是纯数字：解析时从<b>右侧</b>按 {@code _} 切，
 * 编号本身允许含下划线也不会切错；而"谁最老"只需要最后几段里的数字。</p>
 *
 * <p>文件名解析失败一律返回 null（当作不是队列文件跳过），不让一个手工丢进来的
 * 怪文件名把整轮扫描搞崩。</p>
 */
public final class QueueFileName {

    public static final String SUFFIX = ".json";

    private static final char SEPARATOR = '_';
    private static final String DEAD_TIME_PATTERN = "yyyyMMddHHmmss";

    private QueueFileName() {
        // 工具类，不实例化
    }

    /** 待发文件名。 */
    public static String pending(String deliveryId, long arrivedAtMillis) {
        return deliveryId + SEPARATOR + arrivedAtMillis + SUFFIX;
    }

    /** 重试文件名，{@code attempts} 是已试次数。 */
    public static String retry(String deliveryId, long arrivedAtMillis, int attempts) {
        return deliveryId + SEPARATOR + arrivedAtMillis + SEPARATOR + attempts + SUFFIX;
    }

    /** 死信文件名。 */
    public static String dead(String deliveryId, long arrivedAtMillis, long deadAtMillis, String reason) {
        return deliveryId + SEPARATOR + arrivedAtMillis + SEPARATOR
                + formatDeadTime(deadAtMillis) + SEPARATOR + reason + SUFFIX;
    }

    /**
     * 解析一个队列文件。
     *
     * @param directoryName 所在目录名（pending / retry / dead）
     * @param path          文件路径
     * @return 解析结果；文件名不合规则返回 null
     */
    public static QueueFile parse(String directoryName, Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.startsWith(".") || !fileName.endsWith(SUFFIX)) {
            return null;
        }
        String base = fileName.substring(0, fileName.length() - SUFFIX.length());
        if (QueueStore.DIR_PENDING.equals(directoryName)) {
            return parsePending(path, base);
        }
        if (QueueStore.DIR_RETRY.equals(directoryName)) {
            return parseRetry(path, base);
        }
        if (QueueStore.DIR_DEAD.equals(directoryName)) {
            return parseDead(path, base);
        }
        return null;
    }

    private static QueueFile parsePending(Path path, String base) {
        int lastSplit = base.lastIndexOf(SEPARATOR);
        if (lastSplit <= 0) {
            return null;
        }
        Long arrived = parseLong(base.substring(lastSplit + 1));
        if (arrived == null) {
            return null;
        }
        return QueueFile.pending(path, base.substring(0, lastSplit), arrived.longValue());
    }

    private static QueueFile parseRetry(Path path, String base) {
        int attemptsSplit = base.lastIndexOf(SEPARATOR);
        if (attemptsSplit <= 0) {
            return null;
        }
        Long attempts = parseLong(base.substring(attemptsSplit + 1));
        if (attempts == null) {
            return null;
        }
        int arrivedSplit = base.lastIndexOf(SEPARATOR, attemptsSplit - 1);
        if (arrivedSplit <= 0) {
            return null;
        }
        Long arrived = parseLong(base.substring(arrivedSplit + 1, attemptsSplit));
        if (arrived == null) {
            return null;
        }
        return QueueFile.retry(path, base.substring(0, arrivedSplit), arrived.longValue(), attempts.intValue());
    }

    private static QueueFile parseDead(Path path, String base) {
        int reasonSplit = base.lastIndexOf(SEPARATOR);
        if (reasonSplit <= 0) {
            return null;
        }
        String reason = base.substring(reasonSplit + 1);
        int deadTimeSplit = base.lastIndexOf(SEPARATOR, reasonSplit - 1);
        if (deadTimeSplit <= 0 || reason.isEmpty()) {
            return null;
        }
        long deadAtMillis = parseDeadTime(base.substring(deadTimeSplit + 1, reasonSplit));
        if (deadAtMillis <= 0L) {
            return null;
        }
        int arrivedSplit = base.lastIndexOf(SEPARATOR, deadTimeSplit - 1);
        if (arrivedSplit <= 0) {
            return null;
        }
        Long arrived = parseLong(base.substring(arrivedSplit + 1, deadTimeSplit));
        if (arrived == null) {
            return null;
        }
        return QueueFile.dead(path, base.substring(0, arrivedSplit), arrived.longValue(), deadAtMillis, reason);
    }

    /**
     * 文件名是否属于某个投递编号（入队查重用，只比前缀不比内容）。
     *
     * @param fileName   文件名
     * @param deliveryId 已清洗的投递编号
     * @return true 表示同一条消息
     */
    public static boolean belongsTo(String fileName, String deliveryId) {
        return fileName.equals(deliveryId + SUFFIX)
                || fileName.startsWith(deliveryId + SEPARATOR);
    }

    /** 进死信时间格式化成 {@code yyyyMMddHHmmss}，只用于文件名和人眼阅读。 */
    public static String formatDeadTime(long millis) {
        return newDeadTimeFormat().format(new Date(millis));
    }

    /** 解析 {@code yyyyMMddHHmmss}，失败返回 0。 */
    public static long parseDeadTime(String text) {
        try {
            return newDeadTimeFormat().parse(text).getTime();
        } catch (ParseException e) {
            return 0L;
        }
    }

    /**
     * SimpleDateFormat 不是线程安全的，所以每次新建。
     * 队列文件数量在百级、清理按 2 秒一轮，这点开销可以忽略。
     */
    private static SimpleDateFormat newDeadTimeFormat() {
        return new SimpleDateFormat(DEAD_TIME_PATTERN);
    }

    private static Long parseLong(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

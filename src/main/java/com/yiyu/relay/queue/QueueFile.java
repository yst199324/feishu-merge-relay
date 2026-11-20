package com.yiyu.relay.queue;

import java.nio.file.Path;

/**
 * 队列里一个文件所代表的"一条消息"，状态全部来自文件名。
 *
 * <p>为什么不去读文件内容或依赖目录缓存：设计文档 5.3 说得很清楚 ——
 * 到期时间存在文件的<b>修改时间</b>上，首次到达时间存在<b>文件名</b>里。
 * 每轮扫描只需要"列目录 + 比较时间 + 解析名字"，不用读那几十个文件的内容，
 * 开销接近零；进程重启后也不用恢复任何内存状态。</p>
 */
public final class QueueFile {

    private final Path path;
    private final String deliveryId;
    private final long arrivedAtMillis;
    private final int attempts;
    private final long deadAtMillis;
    private final String deadReason;

    private QueueFile(Path path, String deliveryId, long arrivedAtMillis,
                      int attempts, long deadAtMillis, String deadReason) {
        this.path = path;
        this.deliveryId = deliveryId;
        this.arrivedAtMillis = arrivedAtMillis;
        this.attempts = attempts;
        this.deadAtMillis = deadAtMillis;
        this.deadReason = deadReason;
    }

    /** pending 目录里的文件：已试次数恒为 0。 */
    public static QueueFile pending(Path path, String deliveryId, long arrivedAtMillis) {
        return new QueueFile(path, deliveryId, arrivedAtMillis, 0, 0L, null);
    }

    /** retry 目录里的文件：带已试次数。 */
    public static QueueFile retry(Path path, String deliveryId, long arrivedAtMillis, int attempts) {
        return new QueueFile(path, deliveryId, arrivedAtMillis, attempts, 0L, null);
    }

    /** dead 目录里的文件：带进死信时刻与原因标记。 */
    public static QueueFile dead(Path path, String deliveryId, long arrivedAtMillis,
                                 long deadAtMillis, String deadReason) {
        return new QueueFile(path, deliveryId, arrivedAtMillis, 0, deadAtMillis, deadReason);
    }

    public Path getPath() {
        return path;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    /**
     * 首次到达毫秒（跨三个目录都不变）。
     *
     * <p>判断"谁最老""是否超龄"一律看它，<b>绝不看文件修改时间</b>：
     * retry 目录里修改时间被改成了"下次该重试的时刻"，是个未来时间（设计文档 5.2 / 14.13）。</p>
     */
    public long getArrivedAtMillis() {
        return arrivedAtMillis;
    }

    /** 已试次数。pending 为 0，retry 为文件名末尾那个数。 */
    public int getAttempts() {
        return attempts;
    }

    /** 进死信的时刻（毫秒），非死信文件为 0。死信保留期看它。 */
    public long getDeadAtMillis() {
        return deadAtMillis;
    }

    /** 死信原因标记（auth / bad / exhausted / expired），非死信文件为 null。 */
    public String getDeadReason() {
        return deadReason;
    }

    /** 文件名，日志里打印用。 */
    public String fileName() {
        return path.getFileName().toString();
    }

    @Override
    public String toString() {
        return fileName() + "[编号=" + deliveryId + ", 到达=" + arrivedAtMillis
                + ", 已试=" + attempts + ", 死信原因=" + deadReason + "]";
    }
}

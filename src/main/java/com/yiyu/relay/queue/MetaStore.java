package com.yiyu.relay.queue;

import com.yiyu.relay.support.DeliveryIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 元数据存储：投递编号 → 事件名 + 原始 Content-Type。
 *
 * <p>为什么需要它：报文文件里只放报文原文（不许塞信封结构，否则原样透传就做不到了），
 * 而转发时必须原样带回 Content-Type（含 charset，否则中文乱码）和事件名（合并项目日志要打）。</p>
 *
 * <p>为什么按编号命名、不按队列文件名命名：消息会在 pending / retry / dead 之间搬来搬去，
 * 文件名每次都变（次数、进死信时间都会改），按编号命名就能一次写好、不用跟着搬家。</p>
 *
 * <p>元数据写失败只记日志、不影响消息本身：大不了转发时用默认 Content-Type。</p>
 */
@Component
public class MetaStore {

    private static final Logger log = LoggerFactory.getLogger(MetaStore.class);

    private static final String META_SUFFIX = ".properties";
    private static final String KEY_EVENT = "event";
    private static final String KEY_CONTENT_TYPE = "content-type";

    private final QueueDirectories directories;

    public MetaStore(QueueDirectories directories) {
        this.directories = directories;
    }

    /** 元数据目录，清理孤儿文件时用。 */
    public Path directory() {
        return directories.metaDirectory();
    }

    /**
     * 读元数据。缺失或损坏时返回 {@link MessageMeta#EMPTY}。
     *
     * @param deliveryId 投递编号
     * @return 元数据，不会为 null
     */
    public MessageMeta read(String deliveryId) {
        if (DeliveryIds.isBlank(deliveryId)) {
            return MessageMeta.EMPTY;
        }
        Path metaPath = pathOf(deliveryId);
        if (!Files.exists(metaPath)) {
            return MessageMeta.EMPTY;
        }
        Properties stored = new Properties();
        Reader reader = null;
        try {
            reader = new InputStreamReader(Files.newInputStream(metaPath), StandardCharsets.UTF_8);
            stored.load(reader);
            return new MessageMeta(stored.getProperty(KEY_EVENT), stored.getProperty(KEY_CONTENT_TYPE));
        } catch (IOException e) {
            log.warn("读取元数据失败：{}（{}），转发时使用默认 Content-Type", metaPath, e.getMessage());
            return MessageMeta.EMPTY;
        } finally {
            closeQuietly(reader, metaPath);
        }
    }

    /**
     * 写元数据（覆盖）。失败只记 WARN —— 它只是"让转发的头更准确"，不影响消息本身。
     *
     * @param deliveryId 投递编号
     * @param meta       元数据
     */
    public void write(String deliveryId, MessageMeta meta) {
        if (DeliveryIds.isBlank(deliveryId) || meta == null) {
            return;
        }
        Path metaPath = pathOf(deliveryId);
        try {
            delete(deliveryId);
            Files.createFile(metaPath);
            FileAttributes.restrictFile(metaPath);
            writeProperties(metaPath, meta);
        } catch (IOException e) {
            log.warn("写元数据失败：{}（{}），该消息转发时使用默认 Content-Type", metaPath, e.getMessage());
        }
    }

    private void writeProperties(Path metaPath, MessageMeta meta) throws IOException {
        Properties stored = new Properties();
        if (meta.getEventName() != null) {
            stored.setProperty(KEY_EVENT, meta.getEventName());
        }
        if (meta.getContentType() != null) {
            stored.setProperty(KEY_CONTENT_TYPE, meta.getContentType());
        }
        Writer writer = new OutputStreamWriter(Files.newOutputStream(metaPath), StandardCharsets.UTF_8);
        try {
            stored.store(writer, null);
        } finally {
            writer.close();
        }
    }

    /**
     * 删除元数据。消息处理完（成功 / 进死信被淘汰）时调用，避免残留。
     *
     * @param deliveryId 投递编号
     */
    public void delete(String deliveryId) {
        if (DeliveryIds.isBlank(deliveryId)) {
            return;
        }
        Path metaPath = pathOf(deliveryId);
        try {
            Files.deleteIfExists(metaPath);
        } catch (IOException e) {
            log.warn("删除元数据失败：{}（{}）", metaPath, e.getMessage());
        }
    }

    private Path pathOf(String deliveryId) {
        return directory().resolve(deliveryId + META_SUFFIX);
    }

    private void closeQuietly(Reader reader, Path path) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException e) {
            log.debug("关闭元数据文件失败：{}（{}）", path, e.getMessage());
        }
    }
}

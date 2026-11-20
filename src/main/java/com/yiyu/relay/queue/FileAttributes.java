package com.yiyu.relay.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 文件属性工具：修改时间与权限。
 *
 * <p>修改时间在这个服务里有特殊语义，必须集中一处读写，避免有人又把 mtime 当成年龄来用：
 * pending 里 mtime = 到达时间；retry 里 mtime = <b>下次该重试的时刻（未来时间）</b>；
 * dead 里 mtime = 进死信的时刻。判断"谁最老""超龄没有"一律看文件名里的首次到达毫秒。</p>
 *
 * <p>权限（目录 700 / 文件 600）只在 POSIX 文件系统上设置，Windows 开发机跳过，
 * 不能让"设不了权限"把启动搞挂。</p>
 */
public final class FileAttributes {

    private static final Logger log = LoggerFactory.getLogger(FileAttributes.class);

    private static final String DIR_PERMISSIONS = "rwx------";   // 700
    private static final String FILE_PERMISSIONS = "rw-------";  // 600

    private static final boolean POSIX_SUPPORTED =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private FileAttributes() {
        // 工具类，不实例化
    }

    /**
     * 把文件修改时间设成指定毫秒。
     *
     * @param path   文件
     * @param millis 目标时刻
     * @throws IOException 设置失败
     */
    public static void setModifiedTime(Path path, long millis) throws IOException {
        Files.setLastModifiedTime(path, FileTime.fromMillis(millis));
    }

    /**
     * 读文件修改时间。
     *
     * @param path 文件
     * @return 毫秒；读不到时返回 0（当成"已到期"，宁可早试一次也不要永远卡住）
     */
    public static long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            log.warn("读文件修改时间失败：{}（{}）", path, e.getMessage());
            return 0L;
        }
    }

    /**
     * 目录设成 700：报文里有提交人姓名和邮箱，不能让别的用户读到。
     *
     * @param directory 目录
     */
    public static void restrictDirectory(Path directory) {
        applyPosixPermissions(directory, DIR_PERMISSIONS);
    }

    /**
     * 文件设成 600。
     *
     * @param file 文件
     */
    public static void restrictFile(Path file) {
        applyPosixPermissions(file, FILE_PERMISSIONS);
    }

    private static void applyPosixPermissions(Path path, String permissions) {
        if (!POSIX_SUPPORTED) {
            return;
        }
        try {
            Set<PosixFilePermission> permissionSet = PosixFilePermissions.fromString(permissions);
            Files.setPosixFilePermissions(path, permissionSet);
        } catch (IOException e) {
            // 权限设置失败不影响功能（同机部署、单用户），但必须留下痕迹
            log.warn("设置文件权限失败：{}，目标权限 {}", path, permissions, e);
        } catch (UnsupportedOperationException e) {
            log.debug("当前文件系统不支持 POSIX 权限：{}", path);
        }
    }
}

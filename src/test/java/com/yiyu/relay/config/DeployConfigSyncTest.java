package com.yiyu.relay.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署目录里的配置副本必须和源码里那份一致。
 *
 * <p>{@code deploy/application.yml} 为什么要在仓库里留一份：{@code start.sh} 会检查
 * "jar 同级有没有 application.yml"，没有就直接退出。新克隆一个仓库就能部署，
 * 不用先去 src 里翻文件。</p>
 *
 * <p>代价是同一份内容存了两处，会漂移。所以用这条测试卡住：只改一边，构建就红。
 * 这比在文件头写一句"记得同步"可靠得多。</p>
 */
class DeployConfigSyncTest {

    /** Git 在 Windows 上可能把换行改成 CRLF，比较前统一成 LF，否则两边内容一样也会报错。 */
    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在：" + path.toAbsolutePath());
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("deploy/application.yml 与 src/main/resources/application.yml 必须逐字一致")
    void deployYmlMatchesSource() throws IOException {
        String source = read(Paths.get("src/main/resources/application.yml"));
        String deploy = read(Paths.get("deploy/application.yml"));

        assertEquals(source, deploy,
                "两份 application.yml 不一致。改配置时两边都要改："
                        + "src/main/resources/application.yml 是打进 jar 的，"
                        + "deploy/application.yml 是给服务器整份覆盖用的");
    }

    @Test
    @DisplayName("deploy/.env.example 与根目录 .env.example 必须逐字一致")
    void deployEnvExampleMatchesSource() throws IOException {
        String source = read(Paths.get(".env.example"));
        String deploy = read(Paths.get("deploy/.env.example"));

        assertEquals(source, deploy,
                "两份 .env.example 不一致。本地开发用根目录那份，服务器用 deploy 里那份，内容应完全一致");
    }
}

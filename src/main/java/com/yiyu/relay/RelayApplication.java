package com.yiyu.relay;

import com.yiyu.relay.config.RelayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 云效消息监听服务启动类。
 *
 * <p>这个服务只有三件事：验签、写文件、转发。它不解析云效报文、不调云效接口、
 * 不发飞书卡片，所以依赖里没有飞书 SDK 和云效 SDK —— 也几乎不需要跟着业务改动。
 * 设计说明见仓库根目录 README.md 与 {@code feishu-merge-bot/docs/监听服务与卡片容错方案.md}。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(RelayProperties.class)
public class RelayApplication {

    /**
     * 入口。
     *
     * @param args 命令行参数（部署脚本会传 --spring.config.additional-location）
     */
    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}

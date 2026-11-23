package com.yiyu.relay.support;

import com.yiyu.relay.client.ForwardResult;
import com.yiyu.relay.client.RelayClient;
import com.yiyu.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时主动探测一次合并项目内部入口（设计文档 14.8 第②层）。
 *
 * <p>不做会怎样：`INTERNAL_ENDPOINT` 默认 false，部署形态二时忘了打开，
 * 失败日志长得和网络抖动一模一样，排查方向会被带到"网络、防火墙、端口"上绕一大圈。
 * 开机就探一次，结论直接写在启动日志里，不用等第一条真实消息。</p>
 *
 * <p><b>探测失败绝不能让进程起不来</b>：启动顺序本来就无所谓
 * （先起监听服务、后起合并项目是正常情况），这里所有异常都只记日志。</p>
 */
@Component
public class TargetProbe {

    private static final Logger log = LoggerFactory.getLogger(TargetProbe.class);

    private static final String ENDPOINT_MISSING_MESSAGE =
            "合并项目内部入口没开（返回 404/405）：{}。请检查合并项目 .env 里的 INTERNAL_ENDPOINT 是否为 true，"
                    + "以及 relay.target.url 的端口是否正确。在修好之前，转发来的消息会直接进死信目录（原因 bad），不会无限重试";

    private final RelayClient relayClient;
    private final RelayProperties properties;

    public TargetProbe(RelayClient relayClient, RelayProperties properties) {
        this.relayClient = relayClient;
        this.properties = properties;
    }

    /** 应用就绪后探测一次。 */
    @EventListener(ApplicationReadyEvent.class)
    public void probeOnStartup() {
        String targetUrl = properties.getTarget().getUrl();
        log.info("探测合并项目内部入口：{}", targetUrl);
        ForwardResult result;
        try {
            result = relayClient.probe();
        } catch (RuntimeException e) {
            // 探测只是提示，任何意外都不能影响服务启动
            log.warn("探测内部入口时发生意外异常，忽略本次探测：{}（{}）", targetUrl, e.getMessage());
            return;
        }
        report(targetUrl, result);
    }

    private void report(String targetUrl, ForwardResult result) {
        switch (result.getOutcome()) {
            case SUCCESS:
            case PERMANENT:
                // 400 也说明接口是通的（它在抱怨报文），这正是设计文档认可的"可达"结论
                log.info("内部入口可达：{}（{}）", targetUrl, result.describe());
                break;
            case ENDPOINT_MISSING:
                log.error(ENDPOINT_MISSING_MESSAGE, targetUrl);
                break;
            case RETRYABLE:
            default:
                // 这里不能承诺"等它起来后会自动补发"：探针只知道"现在连不上"，
                // 不知道对方起来后内部入口到底开没开。若没开，后续转发会拿到 404，
                // 按设计直接进死信、一次都不重试，只能手工补发。
                // 把前提说清楚，否则运维会以为"等它起来就好了"——而消息已经全进死信了。
                log.warn("合并项目还没起来或暂时连不上：{}（{}）。"
                                + "注意：如果它起来时内部入口没开（INTERNAL_ENDPOINT=false），"
                                + "后续转发会拿到 404，那批消息会直接进死信、不会自动补发；"
                                + "确认入口打开后用 requeue-dead.sh 补",
                        targetUrl, result.describe());
                break;
        }
    }
}

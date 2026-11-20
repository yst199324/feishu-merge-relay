package com.yiyu.relay.client;

/**
 * 转发目标（合并项目内部入口）的抽象。
 *
 * <p>worker 只依赖这个接口，不直接 new {@link RelayClient}：这样单测里可以塞一个
 * 立刻返回固定结论的假实现，不用真的起 HTTP 服务；将来要换成别的转发方式
 * （比如加一个 Nginx 中转、或换成 RestTemplate 实现）也只改实现类。</p>
 */
public interface RelayTarget {

    /**
     * 转发一条消息。
     *
     * <p>约定：本方法不抛受检异常。任何失败都要归类成
     * {@link ForwardOutcome#RETRYABLE} 之类的结论返回，worker 才有统一的处理路径。</p>
     *
     * @param message 待转发消息
     * @return 转发结论
     */
    ForwardResult send(OutgoingMessage message);
}

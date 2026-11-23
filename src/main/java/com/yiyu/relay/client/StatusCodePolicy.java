package com.yiyu.relay.client;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 返回码白名单（设计文档 6.2）。
 *
 * <p>为什么是白名单而不是"if 200 / else if 400 / else 重试"的长链：
 * 明确列出的状态码才有确定语义，没列的一律可重试 —— 这样将来合并项目
 * 多返回一个 409 / 429，也不会因为漏判被判成永久失败而丢掉消息。</p>
 *
 * <p>表本身可以被构造器替换（测试、或将来从配置读），所以归类规则对扩展开放。</p>
 */
@Component
public class StatusCodePolicy {

    /** 已收下的状态码。201/204 是"创建成功/无内容"，同样算成功。 */
    private static final int[] SUCCESS_STATUSES = {200, 201, 204};

    /**
     * 重试无用的状态码。
     *
     * <p>400 报文不合法、413 报文超限、422 语义不合法：重投多少次都是同样的结果。
     * 401/403 是鉴权被拒 —— 令牌不会自己变好，重试只是白耗六档阶梯（累计约 2 小时 37 分）
     * 再进死信，不如立刻落死信、把原因记成 auth，运维才知道"换完令牌就能补"。</p>
     */
    private static final int[] PERMANENT_STATUSES = {400, 401, 403, 413, 422};

    /** 内部入口没开或路径写错。必须单独归类，不能按可重试处理。 */
    private static final int[] ENDPOINT_MISSING_STATUSES = {404, 405};

    private final Map<Integer, ForwardOutcome> outcomeByStatus;

    /** Spring 注入用：使用内置白名单。 */
    public StatusCodePolicy() {
        this(defaultOutcomeByStatus());
    }

    /**
     * 测试/扩展用：自定义状态码表。
     *
     * @param outcomeByStatus 状态码 → 结论，未列出的状态码走可重试兜底
     */
    public StatusCodePolicy(Map<Integer, ForwardOutcome> outcomeByStatus) {
        this.outcomeByStatus = Collections.unmodifiableMap(new HashMap<Integer, ForwardOutcome>(outcomeByStatus));
    }

    private static Map<Integer, ForwardOutcome> defaultOutcomeByStatus() {
        Map<Integer, ForwardOutcome> table = new HashMap<Integer, ForwardOutcome>();
        for (int status : SUCCESS_STATUSES) {
            table.put(status, ForwardOutcome.SUCCESS);
        }
        for (int status : PERMANENT_STATUSES) {
            table.put(status, ForwardOutcome.PERMANENT);
        }
        for (int status : ENDPOINT_MISSING_STATUSES) {
            table.put(status, ForwardOutcome.ENDPOINT_MISSING);
        }
        return table;
    }

    /**
     * 归类一个 HTTP 状态码。
     *
     * @param httpStatus 状态码
     * @return 结论，未在白名单里的一律 {@link ForwardOutcome#RETRYABLE}
     */
    public ForwardOutcome classify(int httpStatus) {
        ForwardOutcome outcome = outcomeByStatus.get(httpStatus);
        return outcome == null ? ForwardOutcome.RETRYABLE : outcome;
    }
}

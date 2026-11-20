package com.yiyu.relay.client;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 判断 400 是不是"云效凭据失效"（设计文档 6.2）。
 *
 * <p>为什么值得单独判断：凭据失效会把一整批消息同时送进死信，
 * 运维需要一句 {@code ls -1 queue/dead/*_auth_*.json} 就筛出该补发的那批。
 * 若原因统一记成 {@code bad}，就分不清"报文坏了（补发也没用）"和
 * "令牌过期了（换完就能补）"。</p>
 *
 * <p>只认 400 / 401 / 403 且响应体命中关键词，避免把"报文里恰好提到 token"
 * 误判成凭据问题。</p>
 */
@Component
public class CredentialFailureDetector {

    /** 合并项目 400 响应里会出现的措辞（"云效凭据失效，请更换 x-yunxiao-token 后手工补发"）。 */
    private static final List<String> DEFAULT_KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "凭据", "令牌", "token", "unauthorized", "认证失败", "401", "403"));

    private static final List<Integer> CREDENTIAL_STATUSES = Collections.unmodifiableList(Arrays.asList(400, 401, 403));

    private final List<String> keywords;

    /** Spring 注入用：使用内置关键词。 */
    public CredentialFailureDetector() {
        this(DEFAULT_KEYWORDS);
    }

    /**
     * 测试/扩展用：自定义关键词。
     *
     * @param keywords 关键词列表（不区分大小写的子串匹配）
     */
    public CredentialFailureDetector(List<String> keywords) {
        this.keywords = keywords == null ? DEFAULT_KEYWORDS : keywords;
    }

    /**
     * 这次失败是不是凭据问题。
     *
     * @param httpStatus 状态码
     * @param body       响应体（可为 null）
     * @return true 表示应按凭据失效处理（死信原因记 auth）
     */
    public boolean indicatesCredentialFailure(int httpStatus, String body) {
        if (!CREDENTIAL_STATUSES.contains(Integer.valueOf(httpStatus)) || body == null || body.isEmpty()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

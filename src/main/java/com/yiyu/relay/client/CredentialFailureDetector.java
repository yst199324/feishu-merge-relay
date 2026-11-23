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
 * <p>判据有三条，优先级见 {@link #indicatesCredentialFailure}：先是合并项目给的机器字段
 * {@code reason=AUTH}，其次是 401 / 403 这两个语义明确的码，最后才是 400 正文里的关键词兜底。
 * 关键词只对 400 生效，避免把"报文里恰好提到 token"误判成凭据问题。</p>
 */
@Component
public class CredentialFailureDetector {

    /** 合并项目 400 响应里会出现的措辞（"云效凭据失效，请更换 x-yunxiao-token 后手工补发"）。 */
    private static final List<String> DEFAULT_KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "凭据", "令牌", "token", "unauthorized", "认证失败", "401", "403"));

    /**
     * 合并项目响应体里的机器字段，格式固定为紧凑 JSON（不带空格）。
     *
     * <p>契约：与合并项目 {@code DispatchResult.REASON_AUTH} 对应。两侧测试都钉了这个字面量，
     * 谁改成 pretty-print 或者改了枚举值，同一次构建就会红。</p>
     */
    private static final String REASON_AUTH_FIELD = "\"reason\":\"AUTH\"";

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
     * <p>三条判据，从可靠到兜底：</p>
     * <ol>
     *   <li><b>响应体里带 {@code reason=AUTH}</b> —— 合并项目明确告诉我们的，最可靠。
     *       那是契约字段不是自然语言，改措辞不影响；</li>
     *   <li><b>状态码 401 / 403</b> —— 本身就是"鉴权被拒"，不必再看文本；</li>
     *   <li><b>400 且正文命中关键词</b> —— 兜底。合并项目现在给的是 400 + 中文说明，
     *       万一它没带 reason（比如监听服务先升级了、它还没），靠这条仍能认出来。</li>
     * </ol>
     *
     * <p>第 1 条和第 3 条并存是刻意的：两个服务可以各自独立升级，
     * 不需要"同时发版"才能正确分拣死信。</p>
     *
     * @param httpStatus 状态码
     * @param body       响应体（可为 null）
     * @return true 表示应按凭据失效处理（死信原因记 auth）
     */
    public boolean indicatesCredentialFailure(int httpStatus, String body) {
        if (body != null && body.contains(REASON_AUTH_FIELD)) {
            return true;
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return true;
        }
        if (httpStatus != 400 || body == null || body.isEmpty()) {
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

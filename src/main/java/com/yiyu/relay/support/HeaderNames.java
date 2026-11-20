package com.yiyu.relay.support;

/**
 * 请求头常量。全部集中在这里，避免字符串散落各处写错一个字符就查半天
 * （{@code X-Codeup-Delivery} 这个名字尤其不能改，见设计文档 6.2）。
 */
public final class HeaderNames {

    /** 云效投递编号。监听服务入队时固化进文件名，转发时原样带回。 */
    public static final String DELIVERY = "X-Codeup-Delivery";

    /** 云效事件名（如 merge_request），合并项目日志里要打。 */
    public static final String EVENT = "X-Codeup-Event";

    /** 云效 Webhook 密钥，只用于监听服务自己验签，不往内网转发。 */
    public static final String TOKEN = "X-Codeup-Token";

    /**
     * 云效历史上还发过这个头（GitLab 的老约定）。合并项目的 WebhookGuard 两种都认，
     * 监听服务必须跟着认：只认一个的话，一旦推送只带老头，每一条都会被 403 丢掉，
     * 而日志看起来像密钥填错 —— 最难查的那类故障。
     */
    public static final String TOKEN_LEGACY = "X-Gitlab-Token";

    /** 报文类型，含 charset，转发时必须原样透传，否则中文会乱码。 */
    public static final String CONTENT_TYPE = "Content-Type";

    /** 内部入口默认的内容类型。仅在云效没带 Content-Type 时兜底。 */
    public static final String DEFAULT_CONTENT_TYPE = "application/json;charset=UTF-8";

    private HeaderNames() {
        // 常量类，不实例化
    }
}

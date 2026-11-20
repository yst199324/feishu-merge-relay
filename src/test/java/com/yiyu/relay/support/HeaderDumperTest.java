package com.yiyu.relay.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求头打印测试。
 *
 * <p>核心一条：{@code X-Codeup-Token} 必须留前 4 位再打码，全打掉就失去了
 * "两次推送是不是同一个密钥"的比对能力。</p>
 */
class HeaderDumperTest {

    @Test
    @DisplayName("密钥类请求头留前 4 位打码，其余原样")
    void masksTokenButKeepsOthers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderNames.TOKEN, "9f1c2f4e8a3b");
        headers.set(HeaderNames.DELIVERY, "a1b2c3d4-0001");
        headers.set(HeaderNames.EVENT, "merge_request");

        String dumped = HeaderDumper.dump(headers);

        assertTrue(dumped.contains("X-Codeup-Token=9f1c********"), dumped);
        assertFalse(dumped.contains("9f1c2f4e8a3b"), "密钥全文不能出现在日志里：" + dumped);
        assertTrue(dumped.contains("X-Codeup-Delivery=a1b2c3d4-0001"), dumped);
        assertTrue(dumped.contains("X-Codeup-Event=merge_request"), dumped);
    }

    @Test
    @DisplayName("X-Gitlab-Token 也按密钥处理（老约定同样不能明文进日志）")
    void masksLegacyToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderNames.TOKEN_LEGACY, "abcd1234efgh");

        String dumped = HeaderDumper.dump(headers);

        assertTrue(dumped.contains("X-Gitlab-Token=abcd********"), dumped);
    }

    @Test
    @DisplayName("空请求头 → {}，不抛异常")
    void emptyHeaders() {
        assertEquals("{}", HeaderDumper.dump(null));
        assertEquals("{}", HeaderDumper.dump(new HttpHeaders()));
    }

    @Test
    @DisplayName("超长非敏感值截到 200 字符，避免一行日志被撑爆")
    void truncatesLongPlainValue() {
        HttpHeaders headers = new HttpHeaders();
        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longValue.append('x');
        }
        headers.set("X-Custom", longValue.toString());

        String dumped = HeaderDumper.dump(headers);

        assertTrue(dumped.contains("..."), dumped);
        assertTrue(dumped.length() < 260, "截断后不该这么长：" + dumped.length());
    }
}

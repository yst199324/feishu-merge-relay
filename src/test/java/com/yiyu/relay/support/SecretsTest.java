package com.yiyu.relay.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打码规则测试。
 *
 * <p>重点守两件事：**留了前 4 位**（不然两份日志没法比对）、**长度不丢**（密钥少了末尾
 * 一个字符这类错，只能靠星号个数看出来）。</p>
 */
class SecretsTest {

    @Test
    @DisplayName("长值 → 前 4 位明文 + 星号，星号个数等于剩余长度")
    void keepsPrefixAndLength() {
        String masked = Secrets.mask("9f1c2f4e8a3b4c5d");

        assertEquals("9f1c************", masked);
        assertEquals("9f1c".length() + 12, masked.length(), "星号个数必须等于被遮掉的字符数");
    }

    @Test
    @DisplayName("短值 → 全留前缀，但至少遮掉 1 位")
    void shortValueStillMasked() {
        assertEquals("a*", Secrets.mask("a"));
        assertEquals("ab*", Secrets.mask("ab"));
        assertEquals("abcd*", Secrets.mask("abcd"));
    }

    @Test
    @DisplayName("空值 → 空串，不抛异常")
    void blankValue() {
        assertEquals("", Secrets.mask(null));
        assertEquals("", Secrets.mask(""));
    }

    @Test
    @DisplayName("超长值 → 星号封顶 32 个，不刷屏")
    void longValueCapped() {
        StringBuilder value = new StringBuilder("abcd");
        for (int i = 0; i < 200; i++) {
            value.append('x');
        }
        String masked = Secrets.mask(value.toString());

        assertTrue(masked.startsWith("abcd"));
        assertEquals("abcd".length() + 32, masked.length());
    }

    @Test
    @DisplayName("名字判定：token / secret / password / cookie 都算敏感，业务字段不算")
    void sensitiveNameDetection() {
        assertTrue(Secrets.isSensitiveName("X-Codeup-Token"));
        assertTrue(Secrets.isSensitiveName("X-Gitlab-Token"));
        assertTrue(Secrets.isSensitiveName("client_secret"));
        assertTrue(Secrets.isSensitiveName("Authorization"));
        assertTrue(Secrets.isSensitiveName("Cookie"));

        assertFalse(Secrets.isSensitiveName("X-Codeup-Delivery"));
        assertFalse(Secrets.isSensitiveName("Content-Type"));
        assertFalse(Secrets.isSensitiveName(null));
    }
}

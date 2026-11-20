package com.yiyu.relay.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报文压行测试。
 *
 * <p>守三件事：敏感字段打码、换行被转义成一行、超长截断并注明原文大小。
 * 第三条尤其重要 —— 截断如果发生在打码之前，一个密钥会被切成两半，后半截就漏出去了。</p>
 */
class BodyDumperTest {

    private static String dump(String json) {
        return BodyDumper.dump(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("JSON 里的 token / secret 字段打码，业务字段原样保留")
    void masksSecretFieldsButKeepsBusiness() {
        String body = dump("{\"object_kind\":\"merge_request\",\"access_token\":\"abcdefgh12345678\","
                + "\"client_secret\":\"zyxwvu98765432\",\"title\":\"修复登录抖动\"}");

        assertTrue(body.contains("\"access_token\":\"abcd************\""), body);
        assertTrue(body.contains("\"client_secret\":\"zyxw**********\""), body);
        assertTrue(body.contains("修复登录抖动"), "业务字段不能被打码：" + body);
    }

    @Test
    @DisplayName("换行 / 制表符转义成字面量，一条消息只占一行")
    void becomesSingleLine() {
        String body = dump("{\n\t\"a\": 1\r\n}");

        assertFalse(body.contains("\n"), "日志里不能出现真换行，否则 grep 会断行");
        assertFalse(body.contains("\r"), "日志里不能出现回车");
        assertTrue(body.contains("\\n"), body);
        assertTrue(body.contains("\\t"), body);
    }

    @Test
    @DisplayName("超长报文截断并注明原文字节数")
    void truncatesAndReportsOriginalSize() {
        StringBuilder big = new StringBuilder("{");
        while (big.length() < BodyDumper.MAX_CHARS + 100) {
            big.append("\"k\":\"v\",");
        }
        big.append("\"end\":1}");
        byte[] bytes = big.toString().getBytes(StandardCharsets.UTF_8);

        String body = BodyDumper.dump(bytes);

        assertTrue(body.length() < bytes.length, "必须真的变短");
        assertTrue(body.endsWith("...(已截断，原文 " + bytes.length + " 字节)"), "末尾要写清原文多大");
    }

    @Test
    @DisplayName("assignee（审批人）不能被误判成 sign 字段而打码")
    void doesNotConfuseAssigneeWithSign() {
        String body = dump("{\"assignee\":\"张三\",\"sign\":\"abcdefgh1234\",\"assignee_id\":7}");

        assertTrue(body.contains("\"assignee\":\"张三\""), "审批人姓名要看得见：" + body);
        assertTrue(body.contains("\"assignee_id\":7"), body);
        assertTrue(body.contains("\"sign\":\"abcd********\""), "sign 是密钥字段，要打码：" + body);
    }

    @Test
    @DisplayName("空报文 → 明确的占位文字，不是空字符串")
    void emptyPayload() {
        assertEquals("(空报文)", BodyDumper.dump(null));
        assertEquals("(空报文)", BodyDumper.dump(new byte[0]));
    }
}

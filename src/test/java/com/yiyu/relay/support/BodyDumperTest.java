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
 * <p>守三件事：正文原样保留（不打码）、换行被转义成一行、超长截断并注明原文大小。</p>
 */
class BodyDumperTest {

    private static String dump(String json) {
        return BodyDumper.dump(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("正文原样保留：token / secret 字段也不打码，否则没法重放")
    void keepsPayloadVerbatim() {
        String json = "{\"object_kind\":\"merge_request\",\"access_token\":\"abcdefgh12345678\","
                + "\"client_secret\":\"zyxwvu98765432\",\"title\":\"修复登录抖动\"}";

        String body = dump(json);

        assertEquals(json, body, "正文必须逐字保留，一个字符都不能改");
        assertFalse(body.contains("*"), "不许打码：" + body);
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
        assertTrue(body.endsWith("...(日志截断，原文 " + bytes.length + " 字节，完整原文在 queue 目录的文件里)"),
                "末尾要写清原文多大、完整原文在哪");
    }

    @Test
    @DisplayName("空报文 → 明确的占位文字，不是空字符串")
    void emptyPayload() {
        assertEquals("(空报文)", BodyDumper.dump(null));
        assertEquals("(空报文)", BodyDumper.dump(new byte[0]));
    }
}

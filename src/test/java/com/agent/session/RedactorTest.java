package com.agent.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 敏感过滤：三条规则各自生效，且不影响正常内容。 */
class RedactorTest {

    @Test
    void shouldRedactWhenToolPathPointsAtEnvFile() {
        Redactor.Result result = Redactor.redact("普通内容", "{\"path\":\".env\"}");

        assertTrue(result.redacted());
        assertEquals(Redactor.PLACEHOLDER, result.text());
    }

    @Test
    void shouldRedactWhenToolPathPointsAtKeyFiles() {
        assertTrue(Redactor.redact("x", "{\"path\":\"certs/server.key\"}").redacted());
        assertTrue(Redactor.redact("x", "{\"path\":\"certs/server.pem\"}").redacted());
        assertTrue(Redactor.redact("x", "{\"path\":\"C:\\\\Users\\\\me\\\\.ssh\\\\id_rsa\"}").redacted());
        assertTrue(Redactor.redact("x", "{\"path\":\"store.p12\"}").redacted());
    }

    @Test
    void shouldKeepNormalPathsAndContent() {
        Redactor.Result result = Redactor.redact("文件内容：public class A {}",
                "{\"path\":\"src/main/java/A.java\"}");

        assertFalse(result.redacted());
        assertEquals("文件内容：public class A {}", result.text());
    }

    @Test
    void shouldRedactKeyValueStyleSecrets() {
        assertTrue(Redactor.redact("DB_PASSWORD = hunter2").redacted());
        assertTrue(Redactor.redact("api_key: abc123").redacted());
        assertTrue(Redactor.redact("token=xyz").redacted());
        assertTrue(Redactor.redact("my secret : value").redacted());
    }

    @Test
    void shouldNotRedactMereMentionsInProse() {
        // 只是提到这些词、但没有「键 = 值」形态，不该被过滤（避免把文档内容全部抹掉）
        assertFalse(Redactor.redact("请把 API key 配置到 .env 文件里").redacted());
    }

    @Test
    void shouldRedactKeyLikeStrings() {
        assertTrue(Redactor.redact("key is sk-abcdefghijklmnopqrstuvwxyz012345").redacted());
        assertFalse(Redactor.redact("sk-short").redacted());
    }

    @Test
    void shouldDetectSensitivePathWithoutContent() {
        assertTrue(Redactor.touchesSensitivePath("{\"path\":\"config/app.pem\"}"));
        assertFalse(Redactor.touchesSensitivePath("{\"path\":\"README.md\"}"));
        assertFalse(Redactor.touchesSensitivePath(null));
    }
}

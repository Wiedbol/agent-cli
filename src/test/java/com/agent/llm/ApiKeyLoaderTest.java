package com.agent.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiKeyLoaderTest {

    private static final String KEY = ApiKeyLoader.DEEPSEEK_KEY;

    @TempDir
    Path tempDir;

    @Test
    void shouldParseKeyIgnoringBomCommentsAndBlankLines() {
        String content = "\uFEFF# DeepSeek 配置\n\nDEEPSEEK_API_KEY=sk-abc123\nOTHER=xxx\n";

        assertEquals("sk-abc123", ApiKeyLoader.parse(content, KEY));
    }

    @Test
    void shouldStripSurroundingQuotes() {
        assertEquals("sk-quoted", ApiKeyLoader.parse("DEEPSEEK_API_KEY=\"sk-quoted\"", KEY));
        assertEquals("sk-single", ApiKeyLoader.parse("DEEPSEEK_API_KEY='sk-single'", KEY));
    }

    @Test
    void shouldNotMatchSimilarKeyNames() {
        assertNull(ApiKeyLoader.parse("DEEPSEEK_API_KEY_BACKUP=sk-other", KEY));
        assertNull(ApiKeyLoader.parse("OTHER_KEY=sk-other", KEY));
    }

    @Test
    void shouldLoadFromEnvFileWhenPresent() throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "DEEPSEEK_API_KEY=sk-from-file\n", StandardCharsets.UTF_8);

        String key = ApiKeyLoader.load(envFile, KEY, name -> "sk-from-env");

        assertEquals("sk-from-file", key, "配置文件优先于环境变量");
    }

    @Test
    void shouldFallBackToEnvironmentVariable() throws Exception {
        Path missing = tempDir.resolve("no-such.env");
        Map<String, String> env = Map.of(KEY, "sk-from-env");
        Function<String, String> lookup = env::get;

        assertEquals("sk-from-env", ApiKeyLoader.load(missing, KEY, lookup));
    }

    @Test
    void shouldReturnNullWhenNeitherSourceHasTheKey() throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "OTHER_KEY=1\n", StandardCharsets.UTF_8);

        assertNull(ApiKeyLoader.load(envFile, KEY, name -> null));
        assertNull(ApiKeyLoader.load(envFile, KEY, null));
        assertNull(ApiKeyLoader.load(null, KEY, name -> "  "));
    }
}

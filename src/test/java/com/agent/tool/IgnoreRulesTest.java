package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IgnoreRulesTest {

    private static final Path ROOT = Path.of(".");

    @Test
    void shouldIgnoreDirectoryRuleAndItsContent() {
        IgnoreRules rules = IgnoreRules.defaults();
        assertTrue(rules.isIgnored(Path.of("target"), ROOT));
        assertTrue(rules.isIgnored(Path.of("target/classes/A.class"), ROOT));
        assertFalse(rules.isIgnored(Path.of("src/main/java/A.java"), ROOT));
    }

    @Test
    void shouldIgnoreGlobByFileName() {
        IgnoreRules rules = IgnoreRules.defaults();
        assertTrue(rules.isIgnored(Path.of("A.class"), ROOT));
        assertTrue(rules.isIgnored(Path.of("logo.png"), ROOT));
        assertFalse(rules.isIgnored(Path.of("notes.txt"), ROOT));
    }

    @Test
    void shouldSkipCommentsAndBlankLines() {
        IgnoreRules rules = IgnoreRules.parse(List.of("# 这是注释", "", "   ", "secret/"));
        assertEquals(List.of("secret/"), rules.patterns());
        assertTrue(rules.isIgnored(Path.of("secret/keys.pem"), ROOT));
    }

    @Test
    void shouldFallBackToDefaultsWhenConfigMissing(@TempDir Path dir) throws IOException {
        IgnoreRules rules = IgnoreRules.load(dir.resolve("no-such-file"));
        assertTrue(rules.patterns().contains("target/"));
    }

    @Test
    void shouldLoadRulesFromConfigFile(@TempDir Path dir) throws IOException {
        Path config = dir.resolve(".agentignore");
        Files.writeString(config, "build/\n*.log\n");

        IgnoreRules rules = IgnoreRules.load(config);

        assertTrue(rules.isIgnored(dir.resolve("build/output.txt"), dir));
        assertTrue(rules.isIgnored(dir.resolve("app.log"), dir));
        assertFalse(rules.isIgnored(dir.resolve("app.txt"), dir));
    }
}

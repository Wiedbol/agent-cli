package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListFilesToolTest {

    private final ListFilesTool tool = new ListFilesTool();

    @Test
    void shouldListFilesRecursivelyAsSortedRelativePaths(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("B.java"), "b");
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/A.java"), "a");

        assertEquals(List.of("B.java", "src/main/A.java"), tool.listFiles(dir));
    }

    @Test
    void shouldListOnlyFilesNotDirectories(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("empty/nested"));
        Files.writeString(dir.resolve("only.java"), "x");

        assertEquals(List.of("only.java"), tool.listFiles(dir));
    }

    @Test
    void shouldApplyDefaultIgnoreRules(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.writeString(dir.resolve("target/classes/Generated.java"), "generated");
        Files.writeString(dir.resolve("A.class"), "bytecode");
        Files.writeString(dir.resolve("Kept.java"), "source");

        assertEquals(List.of("Kept.java"), tool.listFiles(dir));
    }

    @Test
    void shouldRespectMaxDepth(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(dir.resolve("top.txt"), "1");
        Files.writeString(dir.resolve("a/mid.txt"), "2");
        Files.writeString(dir.resolve("a/b/deep.txt"), "3");

        assertEquals(List.of("top.txt"), tool.listFiles(dir, 1));
        assertEquals(List.of("a/mid.txt", "top.txt"), tool.listFiles(dir, 2));
        assertEquals(List.of("a/b/deep.txt", "a/mid.txt", "top.txt"), tool.listFiles(dir, 3));
    }

    @Test
    void shouldRejectNegativeMaxDepth(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> tool.listFiles(dir, -1));
    }

    @Test
    void shouldThrowWhenRootDoesNotExist(@TempDir Path dir) {
        Path missing = dir.resolve("no-such-dir");
        NoSuchFileException thrown =
                assertThrows(NoSuchFileException.class, () -> tool.listFiles(missing));
        assertTrue(thrown.getMessage().contains("no-such-dir"));
    }

    @Test
    void shouldRenderTreeWithIndentation(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("README.md"), "r");
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/A.java"), "a");

        List<String> tree = tool.tree(dir);

        assertEquals(List.of("README.md", "src/", "  main/", "    A.java"), tree);
    }

    @Test
    void shouldUseInjectedIgnoreRules(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("secret"));
        Files.writeString(dir.resolve("secret/hidden.txt"), "x");
        Files.writeString(dir.resolve("open.txt"), "x");

        ListFilesTool custom = new ListFilesTool(IgnoreRules.parse(List.of("secret/", "*.txt")));

        assertEquals(List.of(), custom.listFiles(dir));
    }
}

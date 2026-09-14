package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileToolTest {

    private final ReadFileTool tool = new ReadFileTool();

    @Test
    void shouldReadWholeFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("A.java");
        Files.writeString(file, "class A {}\n");

        assertEquals("class A {}\n", tool.readFile(file));
    }

    @Test
    void shouldReadUtf8Content(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("zh.txt");
        Files.writeString(file, "你好，Agent！");

        assertEquals("你好，Agent！", tool.readFile(file));
    }

    @Test
    void shouldThrowNoSuchFileExceptionWhenMissing(@TempDir Path dir) {
        Path missing = dir.resolve("not-exist.txt");

        NoSuchFileException thrown =
                assertThrows(NoSuchFileException.class, () -> tool.readFile(missing));

        assertTrue(thrown.getMessage().contains("not-exist.txt"));
    }

    @Test
    void shouldThrowNoSuchFileExceptionWhenPathIsDirectory(@TempDir Path dir) {
        assertThrows(NoSuchFileException.class, () -> tool.readFile(dir));
    }

    @Test
    void shouldThrowOnNonUtf8Content(@TempDir Path dir) throws IOException {
        Path binary = dir.resolve("image.dat");
        Files.write(binary, new byte[] {(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, (byte) 0xFE, 0x00});

        assertThrows(MalformedInputException.class, () -> tool.readFile(binary));
    }

    @Test
    void shouldReadLineSlice(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("lines.txt");
        Files.writeString(file, "L1\nL2\nL3\nL4\nL5\n");

        assertEquals("L2\nL3", tool.readFile(file, 1, 2));
    }

    @Test
    void shouldReadTailWhenLimitExceedsRemainingLines(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("lines.txt");
        Files.writeString(file, "L1\nL2\nL3\n");

        assertEquals("L3", tool.readFile(file, 2, 10));
    }

    @Test
    void shouldReturnEmptyStringWhenOffsetBeyondEnd(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("lines.txt");
        Files.writeString(file, "L1\nL2\n");

        assertEquals("", tool.readFile(file, 5, 3));
    }

    @Test
    void shouldRejectInvalidPagingArguments(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("lines.txt");
        Files.writeString(file, "L1\n");

        assertThrows(IllegalArgumentException.class, () -> tool.readFile(file, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> tool.readFile(file, 0, 0));
    }
}

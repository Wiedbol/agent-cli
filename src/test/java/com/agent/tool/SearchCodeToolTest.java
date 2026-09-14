package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchCodeToolTest {

    private final SearchCodeTool tool = new SearchCodeTool();

    @Test
    void shouldFindKeywordWithLineNumberAndContent(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("A.java"), "public class A {\n    // TODO fix me\n}\n");

        List<SearchCodeTool.Match> matches = tool.searchCode("TODO", dir);

        assertEquals(List.of(new SearchCodeTool.Match("A.java", 2, "// TODO fix me")), matches);
    }

    @Test
    void shouldSearchSubdirectoriesWithForwardSlashPaths(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/A.java"), "TODO\n");

        List<SearchCodeTool.Match> matches = tool.searchCode("TODO", dir);

        assertEquals("src/main/A.java", matches.get(0).file());
    }

    @Test
    void shouldFindEveryMatchInOneFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("A.java"), "TODO\nother\nTODO\n");

        List<SearchCodeTool.Match> matches = tool.searchCode("TODO", dir);

        assertEquals(List.of(1, 3), matches.stream().map(SearchCodeTool.Match::line).toList());
    }

    @Test
    void shouldSkipBinaryExtensionsEvenIfTheyContainTheKeyword(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("A.java"), "TODO\n");
        Files.write(dir.resolve("B.png"), "TODO".getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("C.class"), "TODO\n");

        List<SearchCodeTool.Match> matches = tool.searchCode("TODO", dir);

        assertEquals(1, matches.size());
        assertEquals("A.java", matches.get(0).file());
    }

    @Test
    void shouldSkipIgnoredDirectories(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.writeString(dir.resolve("target/classes/Generated.java"), "TODO\n");
        Files.writeString(dir.resolve("A.java"), "TODO\n");

        List<SearchCodeTool.Match> matches = tool.searchCode("TODO", dir);

        assertEquals(1, matches.size());
        assertEquals("A.java", matches.get(0).file());
    }

    @Test
    void shouldSkipUndecodableFileAndKeepSearching(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("A.java"), "TODO\n");
        // 非 UTF-8 字节 + 关键字：若能解码就会被命中，用于验证「跳过而非中断」
        Files.write(dir.resolve("raw.dat"), new byte[] {(byte) 0xFF, (byte) 0xFE, 'T', 'O', 'D', 'O'});

        List<SearchCodeTool.Match> matches = tool.searchCode("TODO", dir);

        assertEquals(List.of(new SearchCodeTool.Match("A.java", 1, "TODO")), matches);
    }

    @Test
    void shouldTrimMatchedLineContent(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("A.java"), "      TODO      \n");

        assertEquals("TODO", tool.searchCode("TODO", dir).get(0).content());
    }

    @Test
    void shouldSupportRegexSearch(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("A.java"), "// FIXME later\n// TODO now\n");

        List<SearchCodeTool.Match> matches =
                tool.searchCodeRegex(Pattern.compile("TODO|FIXME"), dir);

        assertEquals(List.of(1, 2), matches.stream().map(SearchCodeTool.Match::line).toList());
    }

    @Test
    void shouldRejectEmptyKeyword(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> tool.searchCode("", dir));
    }

    @Test
    void shouldThrowWhenRootDoesNotExist(@TempDir Path dir) {
        Path missing = dir.resolve("no-such-dir");
        assertThrows(NoSuchFileException.class, () -> tool.searchCode("TODO", missing));
    }
}

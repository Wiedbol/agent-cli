package com.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 统一 Tool 契约：参数读取与路径沙箱。 */
class ToolContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private ToolRequest request(String json) throws IOException {
        return new ToolRequest(root, json == null ? null : MAPPER.readTree(json));
    }

    @Test
    void shouldReadRequiredAndOptionalArguments() throws Exception {
        ToolRequest request = request("{\"path\":\"a.txt\",\"limit\":3}");

        assertEquals("a.txt", request.requireString("path"));
        assertEquals(3, request.optionalLong("limit", 10));
        assertEquals(10, request.optionalLong("missing", 10));
        assertEquals("默认", request.optionalString("missing", "默认"));
        assertTrue(request.has("path"));
        assertFalse(request.has("missing"));
    }

    @Test
    void shouldRejectMissingRequiredArgument() throws Exception {
        ToolRequest request = request("{}");

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> request.requireString("path"));
        assertTrue(failure.getMessage().contains("path"));
    }

    @Test
    void shouldTreatBlankArgumentAsMissing() throws Exception {
        ToolRequest request = request("{\"path\":\"   \"}");

        assertFalse(request.has("path"));
        assertThrows(IllegalArgumentException.class, () -> request.requireString("path"));
    }

    @Test
    void shouldResolveRelativePathInsideRoot() throws Exception {
        Path sub = Files.createDirectories(root.resolve("src"));
        Files.writeString(sub.resolve("A.java"), "class A {}");

        assertEquals(sub.resolve("A.java").normalize(), request("{\"path\":\"src/A.java\"}").resolvePath("path"));
    }

    @Test
    void shouldRejectPathEscapingRoot() throws Exception {
        ToolRequest request = request("{\"path\":\"../outside.txt\"}");

        assertThrows(SecurityException.class, () -> request.resolvePath("path"));
    }

    @Test
    void shouldRejectAbsolutePathOutsideRoot() throws Exception {
        Path outside = root.getParent().resolve("outside.txt");
        ToolRequest request = new ToolRequest(root,
                MAPPER.readTree("{\"path\":\"" + outside.toString().replace('\\', '/') + "\"}"));

        assertThrows(SecurityException.class, () -> request.resolvePath("path"));
    }

    @Test
    void shouldRejectMissingRoot() {
        assertThrows(IllegalArgumentException.class, () -> new ToolRequest(null, null));
    }
}

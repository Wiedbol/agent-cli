package com.agent;

import com.agent.roles.ControllerRole;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI 参数解析：默认值、单任务模式与各开关。 */
class MainOptionsTest {

    @Test
    void shouldUseDefaultsWhenNoArguments() {
        Main.Options options = Main.Options.parse(new String[0]);

        assertNull(options.task());
        assertFalse(options.autoApprove());
        assertFalse(options.local());
        assertFalse(options.upperCase());
        assertNull(options.root());
        assertEquals(ControllerRole.DEFAULT_MAX_STEPS, options.maxSteps());
    }

    @Test
    void shouldParseTaskAndFlags() {
        Main.Options options = Main.Options.parse(new String[]{
                "--task", "把 TODO 汇总成 todo.md", "--yes", "--upper"});

        assertEquals("把 TODO 汇总成 todo.md", options.task());
        assertTrue(options.autoApprove());
        assertTrue(options.upperCase());
    }

    @Test
    void shouldParseShortAliases() {
        Main.Options options = Main.Options.parse(new String[]{"-t", "任务", "-y"});

        assertEquals("任务", options.task());
        assertTrue(options.autoApprove());
    }

    @Test
    void shouldParseRootAndMaxSteps() {
        Main.Options options = Main.Options.parse(new String[]{
                "--root", "sub-project", "--max-steps", "7"});

        assertEquals(Path.of("sub-project"), options.root());
        assertEquals(7, options.maxSteps());
    }

    @Test
    void shouldForceLocalMode() {
        assertTrue(Main.Options.parse(new String[]{"--local"}).local());
    }

    @Test
    void shouldIgnoreUnknownArgumentsAndNull() {
        assertEquals("任务", Main.Options.parse(new String[]{"--unknown", "--task", "任务"}).task());
        assertNull(Main.Options.parse(null).task());
    }
}

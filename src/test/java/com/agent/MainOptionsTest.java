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
    void shouldRequireExplicitMaximumForTaskBudgetIncrease() {
        var options = Main.Options.parse(new String[]{"--task-budget", "id", "--max-steps", "20"});
        assertEquals("id", options.budgetTaskId());
        assertEquals(20, options.maxSteps());
        for (String[] args : new String[][]{{"--task-budget", "id"}, {"--task-budget"},
                {"--task-budget", "id", "--max-steps", "20", "--task", "fix"},
                {"--task-budget", "id", "--max-steps", "20", "--local"},
                {"--task-budget", "id", "--max-steps"}}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(args));
        }
    }

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

    // ---- 会话持久化相关参数（SPEC.md 定稿）----

    @Test
    void shouldDefaultToNoSessionOverrides() {
        Main.Options options = Main.Options.parse(new String[0]);

        assertNull(options.resume());
        assertNull(options.sessionName());
        assertNull(options.sessionDir());
        assertNull(options.exportId());
        assertFalse(options.newSession());
    }

    @Test
    void shouldParseSessionFlags() {
        Main.Options options = Main.Options.parse(new String[]{
                "--new", "--session", "demo1", "--session-dir", "user", "--resume", "latest"});

        assertTrue(options.newSession());
        assertEquals("demo1", options.sessionName());
        assertEquals("user", options.sessionDir());
        assertEquals("latest", options.resume());
    }

    @Test
    void shouldParseExportFlag() {
        assertEquals("latest", Main.Options.parse(new String[]{"--export", "latest"}).exportId());
    }

    @Test
    void shouldParseManagedTaskQueriesAndAcceptanceCommand() {
        assertTrue(Main.Options.parse(new String[]{"--tasks"}).tasks());
        assertEquals("task-id", Main.Options.parse(new String[]{"--task-status", "task-id"}).taskStatus());
        assertEquals("mvn -q test", Main.Options.parse(new String[]{"--task", "修复",
                "--accept-command", "mvn -q test"}).acceptanceCommand());
    }

    @Test
    void shouldRejectAmbiguousManagedTaskModes() {
        for (String[] args : new String[][]{
                {"--task", "fix", "--local"}, {"--task", "fix", "--tasks"},
                {"--tasks", "--resume", "latest"}, {"--task-status"},
                {"--accept-command", "mvn test"}, {"--task", "fix", "--session", "chat"}}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Main.Options.parse(args));
        }
    }

    @Test
    void shouldParseExplicitAcceptanceReagreement() {
        var options = Main.Options.parse(new String[]{"--reagree-acceptance", "task-id",
                "--accept-command", "mvn -q test", "--yes"});
        assertEquals("task-id", options.reagreeId());
        assertEquals("mvn -q test", options.acceptanceCommand());
        assertTrue(options.autoApprove());
    }

    @Test
    void shouldRejectIncompleteOrMixedAcceptanceReagreementModes() {
        for (String[] args : new String[][]{
                {"--reagree-acceptance"}, {"--reagree-acceptance", "id"},
                {"--reagree-acceptance", "id", "--accept-command", "mvn test", "--tasks"},
                {"--reagree-acceptance", "id", "--accept-command", "mvn test", "--local"},
                {"--reagree-acceptance", "id", "--accept-command", "mvn test", "--task", "fix"}}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Main.Options.parse(args));
        }
    }
}

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
    @Test void shouldParsePublicationAndArchivedLibraryCommandsWithoutModelOptions() {
        var approval = SkillCommands.parse(new String[]{"--approve-skill", "candidate", "--skill-digest", "digest",
                "--related-trial", "positive", "--counterexample-trial", "negative", "--trial-control", "E:/control", "--trial-source", "E:/source"});
        assertEquals("APPROVE", approval.action().name());
        assertEquals("positive", approval.review().related());
        assertEquals("digest", approval.review().sha256());
        assertEquals("ATTEST", SkillCommands.parse(new String[]{"--attest-skill-trial", "negative", "--trial-control", "E:/control", "--review-note", "checked actions"}).action().name());
        assertEquals("VERSIONS", SkillCommands.parse(new String[]{"--skill-versions", "--trial-control", "E:/control", "--trial-source", "E:/source"}).action().name());
        assertEquals(Path.of("E:/source"), SkillCommands.parse(new String[]{"--start-skill-batch", "--request-limit", "8", "--trial-control", "E:/control", "--trial-source", "E:/source"}).librarySource());
    }
    @Test void shouldRejectAmbiguousOrAutomaticPublicationArguments() {
        for (String[] args : new String[][] {
                {"--approve-skill", "c"}, {"--skill-versions"}, {"--attest-skill-trial", "n", "--trial-control", "E:/c"},
                {"--reject-skill", "c", "--trial-control", "E:/a", "--trial-source", "E:/s", "--skill-digest", "h", "--review-note", "bad", "--yes"},
                {"--skill-versions", "--trial-control", "E:/a", "--trial-source", "E:/s", "--skill-digest", "h"},
                {"--skill-review", "c", "--trial-control", "E:/a", "--trial-source", "E:/s", "--skill-digest", "h", "--related-trial", "p"},
                {"--skill-candidates", "--trial-control", "E:/a"}}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> SkillCommands.parse(args));
        }
    }
    @org.junit.jupiter.api.Test void shouldParseExplicitCandidateTrialAndReadOnlyQueries() {
        var trial = SkillCommands.parse(new String[]{"--trial-skill", "candidate", "--skill-batch", "batch",
                "--trial-control", "E:/control", "--trial-source", "E:/control/history/source/workspace",
                "--trial-catalog", "E:/catalog.json", "--trial-max-steps", "4"});
        org.junit.jupiter.api.Assertions.assertEquals(4, trial.trial().requests());
        org.junit.jupiter.api.Assertions.assertEquals("candidate", trial.trial().candidate());
        org.junit.jupiter.api.Assertions.assertEquals("id", SkillCommands.parse(new String[]{"--skill-trial", "id", "--trial-control", "E:/control"}).trialId());
        org.junit.jupiter.api.Assertions.assertNotNull(SkillCommands.parse(new String[]{"--skill-trials", "--trial-control", "E:/control"}));
    }
    @org.junit.jupiter.api.Test void shouldRejectIncompleteOrConflictingTrialArguments() {
        for (String[] args : new String[][] {
                {"--trial-skill", "candidate"}, {"--skill-trial", "id"}, {"--trial-source", "x"},
                {"--skill-trials", "--trial-control", "x", "--generate-skill", "exp"},
                {"--skill-trial", "id", "--trial-control", "x", "--trial-max-steps", "4"},
                {"--trial-skill", "c", "--skill-batch", "b", "--trial-control", "a", "--trial-source", "s", "--trial-catalog", "m", "--trial-max-steps", "0"}}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> SkillCommands.parse(args));
        }
    }
    @org.junit.jupiter.api.Test void shouldParseExplicitBoundedSkillCommands() {
        var batch = SkillCommands.parse(new String[]{"--start-skill-batch", "--request-limit", "3"});
        org.junit.jupiter.api.Assertions.assertEquals(3, batch.limit());
        var generate = SkillCommands.parse(new String[]{"--generate-skill", "a,b", "--skill-batch", "batch"});
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("a", "b"), generate.sources());
        org.junit.jupiter.api.Assertions.assertEquals("batch", generate.batch());
        org.junit.jupiter.api.Assertions.assertNull(SkillCommands.parse(new String[]{"--task", "repair"}));
        org.junit.jupiter.api.Assertions.assertNotNull(SkillCommands.parse(new String[]{"--skill-candidates"}));
        org.junit.jupiter.api.Assertions.assertNotNull(SkillCommands.parse(new String[]{"--skill-batches"}));
        org.junit.jupiter.api.Assertions.assertNotNull(SkillCommands.parse(new String[]{"--skill-candidate", "id"}));
        org.junit.jupiter.api.Assertions.assertNotNull(SkillCommands.parse(new String[]{"--skill-batch", "id"}));
    }
    @org.junit.jupiter.api.Test void shouldRejectMixedOrUnboundedSkillCommands() {
        for (String[] args : new String[][] {
                {"--generate-skill", "exp"}, {"--start-skill-batch"}, {"--request-limit", "2"},
                {"--start-skill-batch", "--request-limit", "0"}, {"--skill-candidates", "--task", "repair"},
                {"--skill-batches", "--resume", "latest"}, {"--skill-batch"},
                {"--generate-skill", "exp", "--skill-batch", "b", "--revise-skill", "v"},
                {"--skill-batch", "b", "--request-limit", "2"},
                {"--skill-candidates", "--skill-batches"}, {"--skill-candidates", "--skill-candidates"}}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> SkillCommands.parse(args));
        }
    }
    @Test
    void shouldParseExplicitKnowledgeModeAndKeepExperienceDefault() {
        assertEquals(com.agent.task.KnowledgeMode.EXPERIENCE,
                Main.Options.parse(new String[]{"--task", "repair"}).knowledgeMode());
        assertEquals(com.agent.task.KnowledgeMode.NONE,
                Main.Options.parse(new String[]{"--task", "repair", "--no-experiences"}).knowledgeMode());
        assertEquals(com.agent.task.KnowledgeMode.SKILL,
                Main.Options.parse(new String[]{"--task", "repair", "--knowledge-mode", "skill"}).knowledgeMode());
    }

    @Test
    void shouldRejectInvalidOrConflictingKnowledgeModeOptions() {
        for (String[] args : new String[][]{
                {"--task", "fix", "--knowledge-mode"}, {"--task", "fix", "--knowledge-mode", ""},
                {"--task", "fix", "--knowledge-mode", "--yes"}, {"--task", "fix", "--knowledge-mode", "other"},
                {"--task", "fix", "--knowledge-mode", "skill", "--knowledge-mode", "none"},
                {"--task", "fix", "--knowledge-mode", "none", "--no-experiences"},
                {"--task", "fix", "--no-experiences", "--knowledge-mode", "skill"},
                {"--task", "fix", "--no-experiences", "--no-experiences"},
                {"--resume-task", "id", "--knowledge-mode", "experience"},
                {"--tasks", "--knowledge-mode", "none"}, {"--knowledge-mode", "skill"},
                {"--local", "--knowledge-mode", "none"}}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(args));
        }
        assertEquals(com.agent.task.KnowledgeMode.NONE,
                Main.Options.parse(new String[]{"--task", "fix", "--knowledge-mode", "NONE"}).knowledgeMode());
        assertEquals(com.agent.task.KnowledgeMode.EXPERIENCE,
                Main.Options.parse(new String[]{"--task", "fix", "--knowledge-mode", "experience"}).knowledgeMode());
    }

    @Test
    void shouldExposeHelpWithoutStartingTask() {
        assertTrue(Main.Options.parse(new String[]{"--help"}).help());
        assertTrue(Main.Options.parse(new String[]{"-h"}).help());
        assertNull(Main.Options.parse(new String[]{"--help"}).task());
    }


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

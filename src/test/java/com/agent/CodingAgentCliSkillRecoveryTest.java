package com.agent;

import com.agent.command.*;
import com.agent.llm.*;
import com.agent.task.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliSkillRecoveryTest extends SkillPublicationFixture {
    String published, skill, publishedDigest;
    int effects;
    boolean interruptCommand;
    boolean requireFile;
    static final String REQUIRED = "src/main/java/Prerequisite.txt";
    static final class PowerLoss extends Error { }

    @Override void prepare() throws Exception { super.prepare(requireFile ? GUIDE.replace("业务边界失败", "业务边界失败; requires: " + REQUIRED) : GUIDE); }
    @Override Map<String, String> files(String content, String goal) {
        var files = new TreeMap<>(super.files(content, goal));
        if (requireFile) { files.put(REQUIRED, "capability=v1"); }
        return files;
    }

    @Override CommandRunner commands() {
        var acceptance = super.commands();
        return (dir, command, timeout) -> {
            if (command.equals("mvn test")) { return acceptance.run(dir, command, timeout); }
            assertEquals("echo side-effect", command);
            effects++;
            Files.writeString(root.resolve("effect.txt"), Integer.toString(effects));
            if (interruptCommand) { interruptCommand = false; throw new PowerLoss(); }
            return new CommandResult(0, false, "effect completed", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
    }
    void publishedProject() throws Exception {
        published = publish(preparePair());
        var record = json.readTree(Files.readAllBytes(sourceArchive.resolve(".agent/skills/versions/" + published + ".json")));
        skill = record.path("value").path("skillId").asText(); publishedDigest = record.path("sha256").asText();
        nextCase(false); output.reset();
    }
    CodingAgentCli taskCli(LLMClient model, int budget, boolean auto, String input) {
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, auto, budget, new BufferedReader(new StringReader(input)), stream, stream, null, commands());
    }
    int availability(boolean enabled) {
        String action = enabled ? "enable" : "disable";
        return cli(null, action + " " + published + " " + publishedDigest + "\n").setSkillEnabled(
                control, sourceArchive, published, publishedDigest, enabled, enabled ? "原版已复核" : "暂停复用此版本", true);
    }
    String blockedWrite() throws Exception {
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published)),
                call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed")),
                call("command", "run_command", Map.of("command", "echo side-effect")))));
        assertEquals(1, taskCli(model, 1, false, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        return id("任务");
    }
    @Test void shouldDisableInspectRestorePinnedVersionBeforePendingWorkWithoutReplayingEffects() throws Exception {
        publishedProject();
        Path versionFile = sourceArchive.resolve(".agent/skills/versions/" + published + ".json");
        byte[] immutable = Files.readAllBytes(versionFile);
        String task = blockedWrite();
        assertEquals(1, cli(null, "wrong\n").setSkillEnabled(control, sourceArchive, published, publishedDigest, false, "未批准", true), text());
        assertEquals(1, cli(null, "disable " + published + " " + publishedDigest + "\n").setSkillEnabled(
                control, sourceArchive, published, publishedDigest, false, "非交互", false), text());
        assertEquals(0, availability(false), text());
        assertArrayEquals(immutable, Files.readAllBytes(versionFile));
        output.reset(); assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, published), text());
        assertTrue(text().contains("已停用") && text().contains("暂停复用此版本") && text().contains(publishedDigest), text());
        var never = new FakeLLMClient(List.of());
        output.reset(); assertEquals(1, taskCli(never, 1, true, "").resumeManagedTask(task), text());
        assertTrue(text().contains("已停用") && text().contains("状态: Paused"), text());
        assertEquals(0, never.callCount()); assertEquals(0, effects); assertEquals("dev-broken", Files.readString(root.resolve(SOURCE)));
        assertEquals(0, availability(true), text());
        output.reset(); assertEquals(1, taskCli(never, 1, true, "").resumeManagedTask(task), text());
        assertEquals(0, never.callCount()); assertEquals(1, effects); assertEquals("dev-fixed", Files.readString(root.resolve(SOURCE)));
        assertEquals(0, taskCli(null, 3, true, "").raiseTaskBudget(task, 3), text());
        var finish = new FakeLLMClient(List.of(new LLMResponse("请独立验收", null, 7, 11)));
        output.reset(); assertEquals(0, taskCli(finish, 3, true, "").resumeManagedTask(task), text());
        assertTrue(text().contains("模型请求: 2/3") && text().contains(published), text());
        assertEquals(1, effects); assertTrue(finish.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        var saved = new TaskStore(root).read(task);
        assertEquals(2, saved.progress.decisions.get(0).operations.get(1).attempts); // 首次审批阻塞，恢复后实际执行一次。
        assertArrayEquals(immutable, Files.readAllBytes(versionFile));
        output.reset(); assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, published), text());
        assertTrue(text().contains("原版已复核") && text().contains("暂停复用此版本"), text());
    }
    @Test void shouldPauseBeforePendingWorkForMissingCorruptOrForeignPinnedSourcesAndResumeOriginalAfterRepair() throws Exception {
        publishedProject();
        assertEquals(0, availability(true), text());
        for (String fault : List.of("state-directory", "binding-missing", "version", "guide", "archive", "project", "state-missing", "state-corrupt", "state-null")) {
            nextCase(false); output.reset();
            String task = blockedWrite();
            if (fault.equals("guide")) {
                var checkpoint = new TaskStore(root).read(task);
                var load = checkpoint.progress.decisions.get(0).operations.get(0);
                load.status = TaskRecord.OperationState.RUNNING; load.observation = "";
                checkpoint.checkpoint.messages.removeIf(message -> "load".equals(message.toolCallId()));
                new TaskStore(root).save(checkpoint);
            }
            int completed = effects;
            Path damaged = switch (fault) {
                case "version" -> sourceArchive.resolve(".agent/skills/versions/" + published + ".json");
                case "guide" -> sourceArchive.resolve(".agent/skills/versions/" + published + ".md");
                case "project" -> control.resolve("owner.json");
                case "binding-missing" -> sourceArchive.resolve(".agent/skills/availability-bindings/" + published + ".json");
                default -> sourceArchive.resolve(".agent/skills/availability/" + published + "/" + published + ".json");
            };
            byte[] original = Files.readAllBytes(damaged);
            Path moved = sourceArchive.resolveSibling("workspace-unavailable");
            Path heldState = base.resolve("availability-" + UUID.randomUUID());
            try {
                switch (fault) {
                    case "version", "state-missing", "binding-missing" -> Files.delete(damaged);
                    case "state-directory" -> Files.move(damaged.getParent(), heldState, StandardCopyOption.ATOMIC_MOVE);
                    case "archive" -> Files.move(sourceArchive, moved, StandardCopyOption.ATOMIC_MOVE);
                    case "guide" -> Files.writeString(damaged, "unapproved changed guide");
                    case "state-null" -> Files.writeString(damaged, "null");
                    default -> Files.writeString(damaged, "{}");
                }
                if (fault.equals("guide")) { assertEquals(4, availability(true), text()); }
                var never = new FakeLLMClient(List.of());
                output.reset(); assertEquals(1, taskCli(never, 1, true, "").resumeManagedTask(task), fault + "\n" + text());
                assertTrue(text().contains("状态: Paused") && text().contains("固定技能不可用"), fault + "\n" + text());
                assertEquals(0, never.callCount()); assertEquals(completed, effects);
                assertEquals("dev-broken", Files.readString(root.resolve(SOURCE)));
                var saved = new TaskStore(root).read(task);
                assertEquals(1, saved.progress.requests); assertEquals(published, saved.skills.loaded.get(0).versionId());
            } finally {
                if (fault.equals("archive")) { Files.move(moved, sourceArchive, StandardCopyOption.ATOMIC_MOVE); }
                else if (fault.equals("state-directory")) { Files.move(heldState, damaged.getParent(), StandardCopyOption.ATOMIC_MOVE); }
                else { Files.write(damaged, original); }
            }
            output.reset(); assertEquals(1, taskCli(new FakeLLMClient(List.of()), 1, true, "").resumeManagedTask(task), text());
            assertEquals(completed + 1, effects); assertEquals("dev-fixed", Files.readString(root.resolve(SOURCE)));
            var reliable = new TaskStore(root).read(task);
            assertEquals(1, reliable.checkpoint.messages.stream().filter(message -> "tool".equals(message.role())
                    && message.content() != null && message.content().contains("SKILL_GUIDE_PRIVATE_MARKER")).count());
        }
    }
    @Test void shouldRetainSkillAuditOnExplicitUncertainCommandResolutionWithoutReplayingSuccessfulWrite() throws Exception {
        publishedProject(); interruptCommand = true;
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published)),
                call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed")),
                call("command", "run_command", Map.of("command", "echo side-effect")))));
        assertThrows(PowerLoss.class, () -> taskCli(model, 1, true, "").startSkillTask("修复业务边界", null, control, sourceArchive, false));
        String task = id("任务");
        assertEquals("1", Files.readString(root.resolve("effect.txt")));
        var never = new FakeLLMClient(List.of());
        output.reset(); assertEquals(1, taskCli(never, 1, true, "").resumeManagedTask(task), text());
        assertTrue(text().contains("结果不明"), text()); assertEquals(0, never.callCount()); assertEquals(1, effects);
        assertEquals("dev-fixed", Files.readString(root.resolve(SOURCE)));
        assertEquals(1, taskCli(null, 1, true, "replan " + task + "\n").replanTask(task, true), text());
        var saved = new TaskStore(root).read(task);
        assertEquals(0, saved.resolutions.size());
        String operation = saved.progress.decisions.get(0).operations.get(2).reference(1);
        String approval = "stopped " + task + " " + operation + "\nretry " + task + " " + operation + "\n";
        assertEquals(1, taskCli(null, 1, true, approval).retryUncertainOperation(task, operation, true), text());
        output.reset(); assertEquals(0, taskCli(null, 1, true, "").showTask(task), text());
        saved = new TaskStore(root).read(task);
        assertEquals(10, saved.schemaVersion); assertEquals(published, saved.skills.loaded.get(0).versionId());
        assertNotNull(saved.progress.decisions.get(0).skillRequest);
        assertEquals(TaskRecord.OperationState.RUNNING, saved.resolutions.get(0).originalOperation.status);
        output.reset(); assertEquals(1, taskCli(never, 1, true, "").resumeManagedTask(task), text());
        assertEquals(2, effects); assertEquals(0, never.callCount());
        saved = new TaskStore(root).read(task);
        assertEquals(1, saved.progress.decisions.get(0).operations.get(1).attempts);
        assertEquals(0, taskCli(null, 4, true, "").raiseTaskBudget(task, 4), text());
        var finish = new FakeLLMClient(List.of(new LLMResponse("请独立验收", null, 7, 11)));
        output.reset(); assertEquals(0, taskCli(finish, 4, true, "").resumeManagedTask(task), text());
        assertEquals(2, effects); assertTrue(text().contains("模型请求: 2/4"), text());
    }
    @Test void shouldRequireExplicitReplanCancelOldCallsAndRecheckHardConditionsWithoutChangingPins() throws Exception {
        requireFile = true; publishedProject();
        String task = blockedWrite();
        Files.writeString(root.resolve(SOURCE), "human-broken"); Files.delete(root.resolve(REQUIRED));
        var never = new FakeLLMClient(List.of());
        output.reset(); assertEquals(1, taskCli(never, 1, true, "").resumeManagedTask(task), text());
        assertTrue(text().contains("外部"), text());
        assertEquals(1, taskCli(null, 1, true, "").replanTask(task, false), text());
        var saved = new TaskStore(root).read(task); assertEquals(0, saved.resolutions.size());
        assertEquals(1, taskCli(null, 1, true, "replan " + task + "\n").replanTask(task, true), text());
        saved = new TaskStore(root).read(task);
        assertEquals(1, saved.progress.requests); assertEquals(published, saved.skills.loaded.get(0).versionId());
        assertEquals(2, saved.resolutions.get(0).cancelledOperations.size());
        output.reset(); assertEquals(1, taskCli(never, 1, true, "").resumeManagedTask(task), text());
        assertTrue(text().contains("文件前提不满足"), text()); assertEquals(0, effects); assertEquals(0, never.callCount());
        assertEquals("human-broken", Files.readString(root.resolve(SOURCE)));
        Files.writeString(root.resolve(REQUIRED), "capability=v1");
        assertEquals(1, taskCli(null, 1, true, "replan " + task + "\n").replanTask(task, true), text());
        assertEquals(0, taskCli(null, 4, true, "").raiseTaskBudget(task, 4), text());
        var finish = new FakeLLMClient(List.of(calls(call("fresh-edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 7, 11)));
        output.reset(); assertEquals(0, taskCli(finish, 4, true, "").resumeManagedTask(task), text());
        assertTrue(text().contains("模型请求: 3/4") && text().contains(published), text()); assertEquals(0, effects);
    }
    @Test void shouldStopOnAvailabilityPersistenceFailureThenAllowExplicitAbandonAndFreshIndependentTask() throws Exception {
        publishedProject(); String task = blockedWrite();
        Path parent = sourceArchive.resolve(".agent/skills/availability");
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new StringReader("")) {
            @Override public String readLine() throws IOException {
                Files.writeString(parent, "fixture prevents state-directory creation");
                return "disable " + published + " " + publishedDigest;
            }
        };
        var operator = new CodingAgentCli(root, null, true, 1, input, stream, stream, null, commands());
        output.reset(); assertEquals(4, operator.setSkillEnabled(control, sourceArchive, published, publishedDigest, false, "故障夹具", true), text());
        assertFalse(text().contains("版本已停用"), text());
        try {
            var never = new FakeLLMClient(List.of());
            output.reset(); assertEquals(1, taskCli(never, 1, true, "").resumeManagedTask(task), text());
            assertTrue(text().contains("固定技能不可用"), text());
            assertEquals(0, never.callCount()); assertEquals(0, effects); assertEquals("dev-broken", Files.readString(root.resolve(SOURCE)));
        } finally { Files.delete(parent); }
        assertEquals(0, availability(false), text());
        assertEquals(1, taskCli(null, 1, true, "abandon " + task + "\n").abandonTask(task, true), text());
        assertEquals("dev-broken", Files.readString(root.resolve(SOURCE)));
        var old = new TaskStore(root).read(task);
        assertEquals(TaskRecord.State.Abandoned, old.state); assertEquals(1, old.progress.requests);
        var independent = new FakeLLMClient(List.of(calls(call("independent", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 7, 11)));
        output.reset(); assertEquals(0, taskCli(independent, 4, true, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertFalse(independent.requests().toString().contains(published) || independent.requests().toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertEquals(published, new TaskStore(root).read(task).skills.loaded.get(0).versionId());
    }
}

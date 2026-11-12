package com.agent;

import com.agent.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliSkillTrialResumeTest extends SkillTrialFixture {
    @Test void shouldContinueBudgetPausedTrialWithoutReplayingToolsOrReplacingOldSnapshot() throws Exception {
        prepare();
        output.reset();
        assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 1), text());
        batch = id("技能批次"); output.reset();
        var initial = new FakeLLMClient(List.of(calls(
                call("load", "load_skill", Map.of("id", candidate, "version", version)),
                call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed")))));
        assertEquals(1, cli(initial, "").startSkillTrial(config(1)), text());
        String trial = id("技能试用"), task = id("任务");
        Path folder = control.resolve("skill-trials/" + trial);
        byte[] snapshot = Files.readAllBytes(folder.resolve("result-root/.agent/tasks/" + task + "/state.json"));
        var continued = new FakeLLMClient(List.of(new LLMResponse("请独立验收", null, 7, 11)));
        output.reset();
        assertEquals(0, cli(continued, "").resumeSkillTrial(control, trial, 3, "明确批准增加2次", "offline-model", "injected-client"), text());
        assertEquals(1, continued.callCount());
        assertTrue(continued.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertTrue(text().contains("状态: VERIFIED") && text().contains("任务: " + task), text());
        assertTrue(text().contains("试用请求: 2") && text().contains("累计请求: 2/3"), text());
        assertTrue(text().contains("目录已组装=true；指南已组装=true"), text());
        assertTrue(text().contains("工具调用: 2"), text());
        assertEquals("dev-fixed", Files.readString(root.resolve(SOURCE)));
        try (var files = Files.walk(folder.resolve("resumptions"))) {
            var old = files.filter(p -> p.endsWith(Path.of(".agent/tasks/" + task + "/state.json"))).toList();
            assertEquals(1, old.size());
            assertArrayEquals(snapshot, Files.readAllBytes(old.get(0)));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"files", "model", "endpoint", "candidate", "unknown", "nonbudget", "maximum", "pending"})
    void shouldRejectUnsafeContinuationBeforeBudgetMutationOrModelRequest(String invalid) throws Exception {
        prepare();
        LLMClient initial = invalid.equals("unknown") ? (messages, tools) -> { throw new IOException("offline failure"); }
                : new FakeLLMClient(List.of(calls(
                        call("load", "load_skill", Map.of("id", candidate, "version", version)),
                        call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                        new LLMResponse("", null, 1, 1)));
        assertNotEquals(0, cli(initial, "").startSkillTrial(config(invalid.equals("nonbudget") ? 2 : 1)), text());
        String trial = id("技能试用"), task = id("任务");
        Path folder = control.resolve("skill-trials/" + trial);
        if (invalid.equals("files")) { Files.writeString(root.resolve(SOURCE), "external change"); }
        if (invalid.equals("candidate")) { Files.writeString(sourceArchive.resolve(".agent/skills/candidates/" + candidate + ".json"), "{}"); }
        if (invalid.equals("pending")) { Files.writeString(folder.resolve("resume-pending.json"), "{}"); }
        Path taskFile = root.resolve(".agent/tasks/" + task + "/state.json");
        Path batchFile = sourceArchive.resolve(".agent/skills/batches/" + batch + ".json");
        byte[] taskBefore = Files.readAllBytes(taskFile), batchBefore = Files.readAllBytes(batchFile), trialBefore = Files.readAllBytes(folder.resolve("state.json"));
        var never = new FakeLLMClient(List.of());
        output.reset();
        assertEquals(4, cli(never, "").resumeSkillTrial(control, trial, invalid.equals("maximum") ? 1 : 4, "明确人工复核",
                invalid.equals("model") ? "other-model" : "offline-model", invalid.equals("endpoint") ? "other-endpoint" : "injected-client"), text());
        assertEquals(0, never.callCount());
        assertArrayEquals(taskBefore, Files.readAllBytes(taskFile));
        assertArrayEquals(batchBefore, Files.readAllBytes(batchFile));
        assertArrayEquals(trialBefore, Files.readAllBytes(folder.resolve("state.json")));
        assertFalse(Files.exists(folder.resolve("resumptions")));
    }
}

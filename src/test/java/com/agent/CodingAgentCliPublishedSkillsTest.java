package com.agent;

import com.agent.command.*;
import com.agent.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliPublishedSkillsTest extends SkillPublicationFixture {
    String activeSource = SOURCE;
    String published, skill;
    String templateGuide = GUIDE;
    String failureMessage = "boundary calculation expected fixed";
    boolean infrastructureFailure;

    @Test void shouldReviewAttestAndPublishFromSeparateChatWithExactConfirmation() throws Exception {
        var selection = preparePair();
        output.reset();
        assertEquals(0, cli(null, "").reviewSkill(selection), text());
        var hashes = java.util.regex.Pattern.compile("证据摘要: ([a-f0-9]{64})").matcher(text())
                .results().map(result -> result.group(1)).toList();
        assertTrue(hashes.size() >= 2, text());

        Path chatRoot = Files.createDirectory(control.resolve("personal chat"));
        String base = " --root \"" + root + "\" --trial-control \"" + control + "\"";
        String selectionArgs = base + " --trial-source \"" + sourceArchive + "\" --skill-digest " + version
                + " --related-trial " + selection.related() + " --counterexample-trial " + selection.counterexample();
        String approve = "/approve-skill " + candidate + selectionArgs + "\n";
        String input = "/help\n/generate-skill unknown\n/start-skill-batch --request-limit 2\n"
                + "/skill-review " + candidate + selectionArgs + "\n"
                + "/attest-skill-trial " + selection.counterexample() + base + " --review-note '拒绝错误放宽 并核验独立验收'\n"
                + "attest " + selection.counterexample() + " " + version + " " + hashes.get(1) + "\n"
                + approve + "y\n" + approve + "approve " + candidate + " " + version + "\n"
                + "/skill-versions" + base + " --trial-source \"" + sourceArchive + "\"\nexit\n";
        var model = new FakeLLMClient(List.of());
        output.reset();
        var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        var repl = new CodingAgentCli(chatRoot, model, true, 4,
                new java.io.BufferedReader(new java.io.StringReader(input)), stream, stream);
        assertEquals(0, repl.runRepl(null, null, model, true), text());
        assertEquals(0, model.callCount(), text());
        assertTrue(text().contains("技能参数错误") && text().contains("未明确批准，未发布"), text());
        assertTrue(text().contains("拒绝错误放宽 并核验独立验收") && text().contains("已发布"), text());
        assertTrue(Files.isDirectory(chatRoot.resolve(".agent/skills/batches")));
        assertTrue(text().contains("再见"), text());
    }

    @Override void prepare() throws Exception { super.prepare(templateGuide); }
    @Override CommandRunner commands() {
        return (dir, command, timeout) -> {
            if (infrastructureFailure) { return new CommandResult(1, false, "offline infrastructure failure", 1, CommandResult.OutputCompleteness.COMPLETE); }
            boolean fixed = Set.of("fixed", "dev-fixed").contains(Files.readString(root.resolve(activeSource)));
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-RuleTest.xml"), "<testsuite tests=\"1\" failures=\"" + (fixed ? 0 : 1)
                    + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"RuleTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"" + failureMessage + "\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "independent offline fixture", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
    }
    void preparePublished() throws Exception {
        published = publish(preparePair());
        output.reset();
        assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, published), text());
        skill = id("技能");
        nextCase(false);
        activeSource = "src/main/java/feature/NewRule.java";
        Files.createDirectories(root.resolve(activeSource).getParent());
        Files.move(root.resolve(SOURCE), root.resolve(activeSource));
        output.reset();
    }
    record Published(String skill, String version) { }
    Published publishAnother(String guide, String parent) throws Exception {
        activeSource = SOURCE;
        output.reset();
        assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 12), text());
        batch = id("技能批次");
        String experience = com.agent.experience.ExperienceLibrary.readOnlyArchive(sourceArchive, root).list().get(0).id();
        output.reset();
        assertEquals(0, cli(new FakeLLMClient(List.of(new LLMResponse(guide, null, 1, 1))), "").generateSkillCandidate(
                control, sourceArchive, batch, List.of(experience), parent, parent == null ? null : "离线反馈补充步骤"), text());
        candidate = id("技能候选");
        var hash = java.util.regex.Pattern.compile("内容摘要: ([a-f0-9]{64})").matcher(text()); assertTrue(hash.find(), text()); version = hash.group(1);
        nextCase(false); String related = trial(false);
        nextCase(true);
        for (var scenario : json.readTree(Files.readAllBytes(catalog)).path("cases")) {
            if (scenario.path("kind").asText().equals("exclusion")) { json.writeValue(control.resolve("current.json").toFile(), scenario); }
        }
        String counter = trial(true);
        String result = publish(selection(related, counter));
        output.reset(); assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, result), text());
        return new Published(id("技能"), result);
    }
    void resetBusiness() throws Exception {
        nextCase(false); activeSource = "src/main/java/feature/NewRule.java";
        Files.createDirectories(root.resolve(activeSource).getParent()); Files.move(root.resolve(SOURCE), root.resolve(activeSource));
        output.reset();
    }
    CodingAgentCli cli(LLMClient model, int budget) {
        var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, budget, new java.io.BufferedReader(new java.io.StringReader("")), stream, stream, null, commands());
    }
    @Test void shouldDiscoverPublishedVersionAcrossFilesLoadRepairAndInspectWithoutKey() throws Exception {
        preparePublished();
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published))),
                calls(call("accept", "declare_skill", Map.of("id", skill, "version", published, "decision", "ACCEPT", "reason", "新的边界计算适用")),
                        call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 7, 11)));
        assertEquals(0, cli(model, "").startSkillTask("修复不同模块的边界计算", null, control, sourceArchive, false), text());
        String task = id("任务");
        assertTrue(model.requests().get(0).toString().contains(skill));
        assertFalse(model.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertTrue(model.requests().get(1).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertFalse(model.requests().toString().contains("参考经验 ") || model.requests().toString().contains("OTHER_CANDIDATE_SECRET"));
        output.reset();
        assertEquals(0, cli(null, "").showTask(task), text());
        for (String expected : List.of("状态: Verified", "固定技能", skill, published, version, "ACCEPT", "最终请求审计", "模型请求: 3/8", "final: PASSED")) {
            assertTrue(text().contains(expected), text());
        }
        assertEquals("dev-fixed", Files.readString(root.resolve(activeSource)));
    }

    @Test void shouldInvokePublishedSkillAndReturnToSameChat() throws Exception {
        preparePublished();
        var chat = new FakeLLMClient(List.of(new LLMResponse("聊天第一轮", null, 1, 1),
                new LLMResponse("聊天第二轮", null, 1, 1)));
        var managed = new FakeLLMClient(List.of(
                calls(call("load", "load_skill", Map.of("id", skill, "version", published))),
                calls(call("accept", "declare_skill", Map.of("id", skill, "version", published, "decision", "ACCEPT", "reason", "边界适用")),
                        call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 1, 1)));
        var sessions = com.agent.session.SessionPaths.defaultDir(root);
        var session = com.agent.session.SessionStore.start(sessions, com.agent.session.SessionStore.Mode.NEW, "chat");
        var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        var input = new java.io.BufferedReader(new java.io.StringReader(
                "开始聊天\n/skill\n/skill\t修复边界计算\ny\n/trace\n继续聊天\nexit\n"));
        var cli = new CodingAgentCli(root, chat, false, 5, input, stream, stream, session, commands());

        assertEquals(0, cli.runRepl(control, sourceArchive, managed, true), text());
        assertEquals(2, chat.callCount(), text());
        assertEquals(3, managed.callCount(), text());
        assertTrue(managed.requests().get(0).toString().contains(published));
        assertFalse(managed.requests().get(0).toString().contains("开始聊天"));
        assertTrue(chat.requests().get(1).toString().contains("聊天第一轮"));
        assertFalse(chat.requests().get(1).toString().contains("修复边界计算"));
        assertEquals(List.of("开始聊天", "聊天第一轮", "继续聊天", "聊天第二轮"),
                session.loadMessages().messages().stream().map(Message::content).toList());
        var task = new com.agent.task.TaskStore(root).read(id("任务"));
        assertEquals(com.agent.task.KnowledgeMode.SKILL, task.knowledgeMode);
        assertEquals(com.agent.task.TaskRecord.State.Verified, task.state);
        assertEquals(List.of(published), task.skills.loaded.stream().map(com.agent.skill.SkillTask.Reference::versionId).toList());
        assertEquals("dev-fixed", Files.readString(root.resolve(activeSource)));
        assertTrue(text().contains("用法: /skill <修复目标>") && text().contains("y/N > "), text());
        int trace = text().indexOf("最近一次执行过程");
        assertTrue(trace > 0, text());
        String chatOutput = text().substring(0, trace);
        assertTrue(chatOutput.contains("Agent > 请独立验收") && chatOutput.contains(" · Verified · 独立验收 "), text());
        assertFalse(chatOutput.contains("[执行员]") || chatOutput.contains("最终请求审计"), text());
        assertTrue(text().substring(trace).contains("[执行员]") && text().substring(trace).contains("final 验收结果已记录: PASSED"), text());
    }

    @Test void shouldShareApprovedArchiveVersionAndRecheckRevocationAcrossProjects() throws Exception {
        templateGuide = GUIDE.replace("业务边界失败", "业务边界失败; requires: " + SOURCE);
        preparePublished();
        String share = "/share-skill " + published + "\n";
        String unshare = "/unshare-skill " + published + "\n";
        var noModel = new FakeLLMClient(List.of());
        assertEquals(0, cli(noModel, share + share + "exit\n").runRepl(control, sourceArchive, noModel, false), text());
        assertTrue(text().contains("已共享技能版本: " + published), text());
        Path index = Path.of(System.getProperty("user.home"), ".agent-cli", "shared-skills");
        Files.writeString(index.resolve("00000000-0000-0000-0000-000000000000.json"), "{bad");

        Path consumer = Files.createDirectory(base.resolve("consumer"));
        Files.writeString(consumer.resolve("pom.xml"), "<project/>");
        CommandRunner acceptance = (dir, command, timeout) -> {
            Path source = dir.resolve(SOURCE);
            boolean fixed = Files.exists(source) && "fixed".equals(Files.readString(source));
            Path reports = Files.createDirectories(dir.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-RuleTest.xml"), "<testsuite tests=\"1\" failures=\"" + (fixed ? 0 : 1)
                    + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"RuleTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"boundary calculation expected fixed\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "independent offline fixture", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        output.reset();
        var query = consumerCli(consumer, noModel, "/skills\n/skill-info " + published + "\n" + unshare + "exit\n", 1, acceptance);
        assertEquals(0, query.runRepl(null, null, noModel, false), text());
        assertTrue(text().contains("技能版本: " + published) && text().contains("共享来源: ")
                && text().contains("请在共享技能的原项目执行此操作"), text());
        assertTrue(text().contains("短 ID: " + published.substring(0, 8)), text());
        assertEquals(0, noModel.callCount());

        Path plain = Files.createDirectory(base.resolve("plain-chat"));
        String useSkill = "/use-skill " + published.substring(0, 8).toUpperCase(Locale.ROOT) + " 编写一个小程序\n";
        var unused = consumerCli(plain, noModel, useSkill + "exit\n", 1, acceptance);
        assertEquals(0, unused.runRepl(null, null, noModel, false), text());
        assertTrue(text().contains("文件前提不满足"), text());
        assertEquals(0, noModel.callCount());
        Files.createDirectories(plain.resolve(SOURCE).getParent());
        Files.writeString(plain.resolve(SOURCE), "existing file");
        var plainModel = new FakeLLMClient(List.of(new LLMResponse("已参考", null, 1, 1),
                new LLMResponse("普通回复", null, 1, 1)));
        var plainChat = consumerCli(plain, plainModel, useSkill + "普通聊天\nexit\n", 1, acceptance);
        assertEquals(0, plainChat.runRepl(null, null, plainModel, false), text());
        assertTrue(plainModel.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertFalse(plainModel.requests().get(1).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertFalse(Files.exists(plain.resolve(".agent/tasks")));

        output.reset();
        var missing = new FakeLLMClient(List.of(new LLMResponse("待修复", null, 1, 1)));
        assertEquals(1, consumerCli(consumer, missing, "", 1, acceptance).startSkillTask("修复边界", null, null, null, false), text());
        assertFalse(missing.requests().get(0).toString().contains(published));
        Path business = consumer.resolve(SOURCE);
        Files.createDirectories(business.getParent());
        Files.writeString(business, "broken");

        var load = call("load", "load_skill", Map.of("id", skill, "version", published));
        var write = call("write", "write_file", Map.of("path", SOURCE, "content", "fixed"));
        var manual = new FakeLLMClient(List.of(calls(load), calls(write)));
        var revokeDuringApproval = new java.io.BufferedReader(new java.io.StringReader("")) {
            @Override public String readLine() {
                assertEquals(0, cli(noModel, unshare + "exit\n").runRepl(control, sourceArchive, noModel, false), text());
                return "y";
            }
        };
        output.reset();
        var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        var interactive = new CodingAgentCli(consumer, manual, false, 4, revokeDuringApproval, stream, stream, null, acceptance);
        assertEquals(1, interactive.startSkillTask("修复边界", null, null, null, true), text());
        String paused = id("任务");
        var store = new com.agent.task.TaskStore(consumer);
        var saved = store.read(paused);
        assertEquals(com.agent.task.TaskRecord.State.Paused, saved.state);
        assertTrue(saved.reason.contains("共享技能") && saved.reason.contains("已撤销"), saved.reason);
        assertTrue(manual.requests().get(1).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertEquals(root.toString().replace('\\', '/'), saved.skills.sharedProject(published));
        assertEquals(com.agent.task.TaskRecord.OperationState.BLOCKED, saved.progress.decisions.get(1).operations.get(0).status);
        assertEquals("broken", Files.readString(business));
        assertEquals(1, consumerCli(consumer, noModel, "", 4, acceptance).resumeManagedTask(paused, false), text());
        assertEquals(0, noModel.callCount());

        assertEquals(0, cli(noModel, share + "exit\n").runRepl(control, sourceArchive, noModel, false), text());
        var finish = new FakeLLMClient(List.of(new LLMResponse("请独立验收", null, 1, 1)));
        assertEquals(0, consumerCli(consumer, finish, "", 4, acceptance).resumeManagedTask(paused, false), text());
        assertEquals("fixed", Files.readString(business));
        assertEquals(com.agent.task.TaskRecord.State.Verified, store.read(paused).state);

        Files.writeString(business, "broken");
        var staleOffer = new FakeLLMClient(List.of(calls(load), calls(write), new LLMResponse("已独立修复", null, 1, 1)));
        LLMClient revokeBeforeLoad = (messages, tools) -> {
            if (staleOffer.callCount() == 0) {
                assertEquals(0, cli(noModel, unshare + "exit\n").runRepl(control, sourceArchive, noModel, false), text());
            }
            return staleOffer.chat(messages, tools);
        };
        assertEquals(0, consumerCli(consumer, revokeBeforeLoad, "", 4, acceptance).startSkillTask("修复边界", null, null, null, false), text());
        assertTrue(staleOffer.requests().get(0).toString().contains(published));
        assertTrue(staleOffer.requests().get(1).toString().contains("共享技能已撤销或来源不可用"));
        assertFalse(staleOffer.requests().get(1).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));

        assertEquals(0, cli(noModel, share + "exit\n").runRepl(control, sourceArchive, noModel, false), text());
        Files.writeString(sourceArchive.resolve(".agent/skills/versions/" + published + ".md"), "tamper", StandardOpenOption.APPEND);
        output.reset();
        assertEquals(0, consumerCli(consumer, noModel, "/skills\nexit\n", 1, acceptance).runRepl(null, null, noModel, false), text());
        assertTrue(text().contains("暂无已发布技能版本") && !text().contains(published), text());
    }

    private CodingAgentCli consumerCli(Path consumer, LLMClient model, String input, int budget, CommandRunner acceptance) {
        var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        return new CodingAgentCli(consumer, model, true, budget,
                new java.io.BufferedReader(new java.io.StringReader(input)), stream, stream, null, acceptance);
    }

    @Test void shouldQueryAndResumeFixedSkillFromSameChatWithoutResettingBudget() throws Exception {
        preparePublished();
        var first = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published)))));
        assertEquals(1, cli(first, 1).startSkillTask("修复新的边界计算", null, control, sourceArchive, false), text());
        String task = id("任务");
        output.reset();
        var resumed = new FakeLLMClient(List.of(calls(call("reject", "declare_skill", Map.of("id", skill, "version", published,
                        "decision", "REJECT", "reason", "读取后决定独立修复")), call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))),
                new LLMResponse("请验收", null, 7, 11)));
        var chat = new FakeLLMClient(List.of(new LLMResponse("聊天第一轮", null, 1, 1),
                new LLMResponse("聊天第二轮", null, 1, 1)));
        var sessions = com.agent.session.SessionPaths.defaultDir(root);
        var session = com.agent.session.SessionStore.start(sessions, com.agent.session.SessionStore.Mode.NEW, "chat");
        var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        var input = new java.io.BufferedReader(new java.io.StringReader(
                "开始聊天\n/tasks\n/status " + task + "\n/skills\n/skill-info " + published
                        + "\n/task-budget " + task + " 1\n/task-budget " + task + " 4"
                        + "\n/resume-task " + task + "\ny\n/status " + task + "\n继续聊天\nexit\n"));
        var repl = new CodingAgentCli(root, chat, false, 9, input, stream, stream, session, commands());

        assertEquals(0, repl.runRepl(control, sourceArchive, resumed, true), text());
        assertTrue(resumed.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertTrue(text().contains("新总预算必须大于当前总预算"), text());
        assertTrue(text().contains("任务仍暂停，未执行恢复") && text().contains("模型请求: 1/4"), text());
        assertTrue(text().contains("模型请求: 3/4") && text().contains("REJECT"), text());
        assertTrue(text().contains("状态: Paused") && text().contains("状态: Verified"), text());
        assertTrue(text().contains("技能版本: " + published) && text().contains("y/N > "), text());
        assertEquals(2, text().lines().filter(line -> line.endsWith("技能版本: " + published)).count(), text());
        assertEquals(2, chat.callCount());
        assertEquals(List.of("开始聊天", "聊天第一轮", "继续聊天", "聊天第二轮"),
                session.loadMessages().messages().stream().map(Message::content).toList());
        var saved = new com.agent.task.TaskStore(root).read(task);
        assertEquals(com.agent.task.TaskRecord.State.Verified, saved.state);
        assertEquals(1, saved.progress.budgetChanges.size());
        assertEquals(List.of(published), saved.skills.loaded.stream().map(com.agent.skill.SkillTask.Reference::versionId).toList());
        assertEquals(1, saved.skills.events.stream().filter(event -> event.type().equals("LOAD")).count());
        assertEquals(1, first.callCount()); assertEquals(2, resumed.callCount());
    }
    @ParameterizedTest @ValueSource(strings = {"unrelated", "skip", "reject", "requires", "version"})
    void shouldKeepIndependentRepairAndEnforceHardConditions(String choice) throws Exception {
        if (choice.equals("requires")) { templateGuide = GUIDE.replace("业务边界失败", "业务边界失败\\nrequires: " + SOURCE); }
        if (choice.equals("version")) {
            String hash = com.agent.task.WorkspaceSnapshot.hash("<project/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            templateGuide = GUIDE.replace("业务边界失败", "业务边界失败 " + hash);
        }
        preparePublished();
        if (choice.equals("version")) { Files.writeString(root.resolve("pom.xml"), "<project>changed before task</project>"); }
        var responses = new ArrayList<LLMResponse>();
        if (choice.equals("reject")) {
            responses.add(calls(call("load", "load_skill", Map.of("id", skill, "version", published))));
            responses.add(calls(call("reject", "declare_skill", Map.of("id", skill, "version", published, "decision", "REJECT", "reason", "读取后不采用")),
                    call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))));
        } else { responses.add(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed")))); }
        responses.add(new LLMResponse("请验收", null, 1, 1));
        var model = new FakeLLMClient(responses);
        assertEquals(0, cli(model, "").startSkillTask(choice.equals("unrelated") ? "修复语法解析，保持既有验收" : "修复业务边界",
                null, control, sourceArchive, false), text());
        assertFalse(model.requests().toString().contains("参考经验 "));
        assertEquals(choice.equals("reject"), model.requests().toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        if (Set.of("requires", "version").contains(choice)) { assertTrue(text().contains("NO_MATCH"), text()); }
        if (Set.of("unrelated", "skip").contains(choice)) {
            assertTrue(text().contains("固定技能: []") && text().contains("CATALOG"), text());
        }
        if (choice.equals("reject")) { assertTrue(text().contains("REJECT"), text()); }
    }

    @Test void shouldLetAgentChooseFromQualifiedSummariesWithoutKeywordOverlap() throws Exception {
        templateGuide = GUIDE.replace("业务边界失败", "业务边界失败\\nrequires: GOAL.md\\n匹配证据: 临界值触发")
                .replace("不适用于语法问题", "不适用于语法问题\\n排除证据: 正号前缀被接受");
        preparePublished();
        for (String choice : List.of("ACCEPT", "REJECT", "SKIP", "MISSING_FILE")) {
            boolean loads = choice.equals("ACCEPT") || choice.equals("REJECT");
            boolean eligible = !choice.equals("MISSING_FILE");
            String goal = choice.equals("ACCEPT") ? "到指定分数就应该获得资格" : "修正带符号文本的解析";
            failureMessage = choice.equals("ACCEPT") ? "actual false but wanted true" : "正号前缀被接受";
            Files.writeString(root.resolve(activeSource), "broken");
            if (!eligible) { Files.delete(root.resolve("GOAL.md")); }
            output.reset();
            var responses = new ArrayList<LLMResponse>();
            if (loads) {
                responses.add(calls(call("load", "load_skill", Map.of("id", skill, "version", published))));
                responses.add(calls(call("decision", "declare_skill", Map.of("id", skill, "version", published,
                        "decision", choice, "reason", choice.equals("ACCEPT") ? "比较关系导致相等点异常" : "根因是字符解析，应独立处理")),
                        call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))));
            } else {
                responses.add(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))));
            }
            responses.add(new LLMResponse("请验收", null, 1, 1));
            var model = new FakeLLMClient(responses);
            assertEquals(0, cli(model, "").startSkillTask(goal, null, control, sourceArchive, false), text());
            String firstRequest = model.requests().get(0).toString();
            if (eligible) {
                for (String expected : List.of(published, "适用:", "限制:", "来源案例:", "适用性待判断", "无适用项时继续独立诊断和修复")) {
                    assertTrue(firstRequest.contains(expected), firstRequest);
                }
            } else {
                assertFalse(firstRequest.contains(published), firstRequest);
                assertTrue(text().contains("CATALOG_EXCLUDED"), text());
            }
            assertFalse(firstRequest.contains("SKILL_GUIDE_PRIVATE_MARKER"), firstRequest);
            assertEquals(!eligible, text().contains("NO_MATCH"), text());
            assertEquals(loads ? 3 : 2, model.callCount());
            if (!loads) {
                assertTrue(text().contains("固定技能: []"), text());
                assertFalse(model.requests().toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
            } else {
                assertTrue(text().contains(choice), text());
                assertTrue(model.requests().get(1).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
            }
        }
    }
    @Test void shouldAuditActualHttpPayloadAndNotResendFailedRequest() throws Exception {
        preparePublished();
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            for (var response : List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published))),
                    calls(call("accept", "declare_skill", Map.of("id", skill, "version", published, "decision", "ACCEPT", "reason", "边界适用")),
                            call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 2, 3))) {
                var message = json.createObjectNode().put("role", "assistant").put("content", response.content());
                if (response.hasToolCalls()) { message.set("tool_calls", json.valueToTree(response.toolCalls())); }
                var body = json.createObjectNode(); body.putArray("choices").addObject().set("message", message);
                body.putObject("usage").put("prompt_tokens", 2).put("completion_tokens", 3);
                server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()));
            }
            server.start();
            var model = DeepSeekClient.singleAttempt("offline-fixture-only", server.url("/chat/completions").toString(), "offline-model");
            assertEquals(0, cli(model, "").startSkillTask("修复新的边界", null, control, sourceArchive, false), text());
            String first = server.takeRequest().getBody().readUtf8();
            String second = server.takeRequest().getBody().readUtf8();
            String third = server.takeRequest().getBody().readUtf8();
            assertTrue(first.contains(skill) && first.contains(published));
            assertFalse(first.contains("SKILL_GUIDE_PRIVATE_MARKER"));
            assertTrue(second.contains("SKILL_GUIDE_PRIVATE_MARKER") && third.contains("SKILL_GUIDE_PRIVATE_MARKER"));
            assertFalse((first + second + third).contains("OTHER_CANDIDATE_SECRET") || (first + second + third).contains("参考经验 "));
            Files.writeString(root.resolve(activeSource), "dev-broken");
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("offline failure"));
            output.reset();
            assertEquals(1, cli(model, 1).startSkillTask("修复边界", null, control, sourceArchive, false), text());
            assertEquals(4, server.getRequestCount());
            assertTrue(text().contains("模型请求: 1/1") && text().contains("MODEL_FAILED") && text().contains("未知请求=1"), text());
        }
    }
    @Test void shouldOfferAllQualifiedSummariesAndLoadAtMostThreeDistinctSkills() throws Exception {
        preparePublished();
        var available = new ArrayList<Published>(); available.add(new Published(skill, published));
        for (String name : List.of("A", "B", "C")) { available.add(publishAnother(GUIDE.replace("边界诊断", "边界诊断 " + name), null)); }
        available.sort(Comparator.comparing(Published::skill)); resetBusiness();
        var one = available.get(0); var two = available.get(1); var three = available.get(2); var four = available.get(3);
        var model = new FakeLLMClient(List.of(
                calls(call("one", "load_skill", Map.of("id", one.skill(), "version", one.version())), call("two", "load_skill", Map.of("id", two.skill(), "version", two.version()))),
                new LLMResponse("请验证当前修改", null, 1, 1),
                calls(call("three", "load_skill", Map.of("id", three.skill(), "version", three.version())), call("four", "load_skill", Map.of("id", four.skill(), "version", four.version())),
                        call("duplicate", "load_skill", Map.of("id", one.skill(), "version", one.version())), call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 1, 1)));
        assertEquals(0, cli(model, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        String first = model.requests().get(0).toString();
        assertTrue(first.indexOf(one.skill()) < first.indexOf(two.skill()) && first.indexOf(two.skill()) < first.indexOf(three.skill()));
        assertTrue(first.indexOf(three.skill()) < first.indexOf(four.skill()));
        assertTrue(model.requests().get(2).toString().contains(four.skill()));
        String pins = text().lines().filter(line -> line.startsWith("固定技能:")).reduce((a, b) -> b).orElseThrow();
        assertTrue(pins.contains(one.version()) && pins.contains(two.version()) && pins.contains(three.version()) && !pins.contains(four.version()), pins);
        assertTrue(text().contains("同任务最多三种技能") && text().contains("LOAD_DUPLICATE"), text());
        long bodies = model.requests().get(model.requests().size() - 1).stream().filter(m -> "tool".equals(m.role()) && m.content() != null && m.content().contains("SKILL_GUIDE_PRIVATE_MARKER")).count();
        assertEquals(3, bodies);
    }
    @Test void shouldRetainPausedV1AfterV2PublicationAndNeverFallBackFromTamperedDefault() throws Exception {
        preparePublished(); String oldSkill = skill, oldVersion = published;
        var paused = new FakeLLMClient(List.of(calls(call("load-v1", "load_skill", Map.of("id", oldSkill, "version", oldVersion)))));
        assertEquals(1, cli(paused, 1).startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        String task = id("任务"), business = activeSource;
        Path held = base.resolve("paused-root");
        assertTrue(held.startsWith(base.toRealPath()) && root.startsWith(base.toRealPath()));
        // 仅夹具暂存已停止的普通任务，让独立发布流程在原项目身份下准备真实 v2 试用。
        Files.move(root, held, StandardCopyOption.ATOMIC_MOVE); Files.createDirectory(root);
        Files.copy(sourceArchive.resolve(".repair-lab-workspace.json"), root.resolve(".repair-lab-workspace.json"));
        writeFiles(files("dev-broken", "修复新的边界问题"));
        var revised = publishAnother(GUIDE.replace("SKILL_GUIDE_PRIVATE_MARKER", "SKILL_GUIDE_V2_MARKER"), oldVersion);
        assertEquals(oldSkill, revised.skill());
        Files.move(root, base.resolve("v2-result"), StandardCopyOption.ATOMIC_MOVE);
        Files.move(held, root, StandardCopyOption.ATOMIC_MOVE); activeSource = business;
        assertEquals(0, cli(null, 4).raiseTaskBudget(task, 4), text()); output.reset();
        var resumed = new FakeLLMClient(List.of(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, 4).resumeManagedTask(task), text());
        assertTrue(resumed.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertFalse(resumed.requests().toString().contains("SKILL_GUIDE_V2_MARKER"));
        Files.writeString(root.resolve(activeSource), "dev-broken"); output.reset();
        var fresh = new FakeLLMClient(List.of(calls(call("load-v2", "load_skill", Map.of("id", oldSkill, "version", revised.version()))),
                calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(fresh, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertTrue(fresh.requests().get(0).toString().contains(revised.version()));
        assertTrue(fresh.requests().get(1).toString().contains("SKILL_GUIDE_V2_MARKER"));
        String oldDigest = json.readTree(Files.readAllBytes(sourceArchive.resolve(".agent/skills/versions/" + oldVersion + ".json"))).path("sha256").asText();
        String newDigest = json.readTree(Files.readAllBytes(sourceArchive.resolve(".agent/skills/versions/" + revised.version() + ".json"))).path("sha256").asText();
        assertEquals(0, cli(null, "disable " + oldVersion + " " + oldDigest + "\n").setSkillEnabled(
                control, sourceArchive, oldVersion, oldDigest, false, "仅停用旧版", true), text());
        assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, revised.version()), text());
        assertEquals(0, cli(null, "enable " + oldVersion + " " + oldDigest + "\n").setSkillEnabled(
                control, sourceArchive, oldVersion, oldDigest, true, "原版复核", true), text());
        assertEquals(0, cli(null, "disable " + revised.version() + " " + newDigest + "\n").setSkillEnabled(
                control, sourceArchive, revised.version(), newDigest, false, "暂不复用新版", true), text());
        Files.writeString(root.resolve(activeSource), "dev-broken"); output.reset();
        var disabledDefault = new FakeLLMClient(List.of(calls(call("independent", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(disabledDefault, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertFalse(disabledDefault.requests().get(0).toString().contains(oldVersion) || disabledDefault.requests().get(0).toString().contains(revised.version()), "disabled latest must not fall back to v1");
        assertEquals(0, cli(null, "enable " + revised.version() + " " + newDigest + "\n").setSkillEnabled(
                control, sourceArchive, revised.version(), newDigest, true, "恢复原新版", true), text());
        Path file = sourceArchive.resolve(".agent/skills/versions/" + revised.version() + ".json");
        var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.path("value")).put("approvedAt", 1);
        wrapper.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(wrapper.path("value"))));
        json.writeValue(file.toFile(), wrapper);
        Files.writeString(root.resolve(activeSource), "dev-broken"); output.reset();
        var independent = new FakeLLMClient(List.of(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(independent, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertFalse(independent.requests().get(0).toString().contains(oldVersion), "invalid latest must not select old default");
        assertTrue(text().contains("默认版本未知，不回退旧版本"), text());
    }
    void plainProject() throws Exception {
        root = Files.createDirectory(base.resolve("plain")); writeFiles(files("dev-broken", "修复边界"));
    }
    String plainPaused() throws Exception {
        plainProject();
        assertEquals(1, cli(new FakeLLMClient(List.of(new LLMResponse("尚未修复", null, 1, 1))), 1)
                .startManagedTask("修复边界", null, false, com.agent.task.KnowledgeMode.SKILL), text());
        return id("任务");
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "null", "pin-null", "events", "first", "request", "audit"})
    void shouldRejectMalformedSkillStateBeforeAnyRecoveryRequest(String change) throws Exception {
        String task = plainPaused();
        assertEquals(0, cli(null, 4).raiseTaskBudget(task, 4), text());
        Path file = root.resolve(".agent/tasks/" + task + "/state.json");
        var record = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) record.path("skills");
        switch (change) {
            case "missing" -> record.remove("skills");
            case "null" -> record.putNull("skills");
            case "pin-null" -> ((com.fasterxml.jackson.databind.node.ArrayNode) state.path("loaded")).addNull();
            case "events" -> state.remove("events");
            case "first" -> state.remove("firstRequest");
            case "request" -> ((com.fasterxml.jackson.databind.node.ObjectNode) record.path("progress").path("decisions").get(0)).remove("skillRequest");
            case "audit" -> ((com.fasterxml.jackson.databind.node.ObjectNode) record.path("progress").path("decisions").get(0).path("skillRequest").path("payload").path("messages").get(0)).put("content", "forged final context");
            default -> throw new AssertionError(change);
        }
        record.remove("checkpointSha256"); record.put("checkpointSha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(record)));
        json.writeValue(file.toFile(), record); output.reset();
        var never = new FakeLLMClient(List.of());
        assertEquals(4, cli(never, 4).resumeManagedTask(task), text());
        assertEquals(0, never.callCount());
    }
    @ParameterizedTest @ValueSource(strings = {"candidate", "wrong-version", "joined-id", "duplicate", "undeclared"})
    void shouldRefuseUnauthorizedLoadsAndDistinguishMissingDeclaration(String choice) throws Exception {
        preparePublished();
        var operations = new ArrayList<ToolCall>();
        String id = switch (choice) {
            case "candidate" -> otherCandidate;
            case "joined-id" -> skill + "@" + published;
            default -> skill;
        };
        operations.add(call("load", "load_skill", Map.of("id", id,
                "version", choice.equals("wrong-version") ? otherCandidate : published)));
        if (choice.equals("duplicate")) { operations.add(call("again", "load_skill", Map.of("id", skill, "version", published))); }
        operations.add(call("outside", "read_file", Map.of("path", sourceArchive.resolve(".agent/skills/candidates/" + otherCandidate + ".md").toString())));
        operations.add(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed")));
        var model = new FakeLLMClient(List.of(new LLMResponse(null, operations, 1, 1), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertFalse(model.requests().toString().contains("OTHER_CANDIDATE_SECRET"));
        if (Set.of("candidate", "wrong-version", "joined-id").contains(choice)) {
            assertTrue(text().contains("LOAD_DENIED") && text().contains("固定技能: []"), text());
            assertTrue(model.requests().get(1).toString().contains("请分别填写 id 和 version"));
        }
        else { assertTrue(text().contains("未声明"), text()); }
        if (choice.equals("duplicate")) {
            assertTrue(text().contains("LOAD_DUPLICATE"), text());
            assertEquals(1, model.requests().get(1).stream().filter(m -> "tool".equals(m.role()) && m.content() != null && m.content().contains("SKILL_GUIDE_PRIVATE_MARKER")).count());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"failed", "unknown", "persistence"})
    void shouldReserveAuditBeforeClientAndRetainUnknownWithoutExecutingUnrecordedTools(String choice) throws Exception {
        plainProject();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var taskId = new java.util.concurrent.atomic.AtomicReference<String>();
        LLMClient model = (messages, tools) -> {
            count.incrementAndGet(); taskId.set(id("任务"));
            assertEquals(0, cli(null, 1).showTask(taskId.get()), text());
            assertTrue(text().contains("RESERVED") && text().contains("最终请求审计 1"), text());
            if (choice.equals("failed")) { throw new java.io.IOException("offline transport failed"); }
            if (choice.equals("unknown")) { throw new AssertionError("fixture stops after saved reservation"); }
            Path state = root.resolve(".agent/tasks/" + taskId.get() + "/state.json");
            Files.delete(state); Files.createDirectory(state);
            try { return calls(call("must-not-run", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))); }
            catch (Exception failure) { throw new java.io.IOException(failure); }
        };
        if (choice.equals("unknown")) { assertThrows(AssertionError.class, () -> cli(model, 1).startManagedTask("修复边界", null, false, com.agent.task.KnowledgeMode.SKILL)); }
        else { assertEquals(choice.equals("persistence") ? 4 : 1, cli(model, 1).startManagedTask("修复边界", null, false, com.agent.task.KnowledgeMode.SKILL), text()); }
        assertEquals(1, count.get()); assertEquals("dev-broken", Files.readString(root.resolve(activeSource)));
        if (!choice.equals("persistence")) {
            output.reset(); assertEquals(0, cli(null, 1).showTask(taskId.get()), text());
            assertTrue(text().contains("模型请求: 1/1") && text().contains("未知请求=1"), text());
        }
        if (choice.equals("unknown")) {
            var never = new FakeLLMClient(List.of());
            assertEquals(1, cli(never, 1).resumeManagedTask(taskId.get()), text()); assertEquals(0, never.callCount());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"passed", "unusable"})
    void shouldNotDiscoverWithoutUsableFailedBaseline(String condition) throws Exception {
        preparePublished();
        if (condition.equals("passed")) { Files.writeString(root.resolve(activeSource), "dev-fixed"); }
        else { infrastructureFailure = true; }
        var model = new FakeLLMClient(condition.equals("passed") ? List.of(new LLMResponse("请验收", null, 1, 1)) : List.of());
        assertEquals(condition.equals("passed") ? 0 : 1, cli(model, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertEquals(condition.equals("passed") ? 1 : 0, model.callCount());
        assertFalse(text().contains("type=CATALOG"), text());
        assertFalse(model.requests().toString().contains(skill) || model.requests().toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
    }
    @Test void shouldRejectLostPinsEvenWhenOldGuideRemainsInReliableHistory() throws Exception {
        preparePublished();
        var load = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published)))));
        assertEquals(1, cli(load, 1).startSkillTask("修复边界", null, control, sourceArchive, false), text());
        String task = id("任务"); assertEquals(0, cli(null, 4).raiseTaskBudget(task, 4), text());
        Path file = root.resolve(".agent/tasks/" + task + "/state.json");
        var record = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) record.path("skills");
        state.putArray("offered"); state.putArray("loaded");
        record.remove("checkpointSha256"); record.put("checkpointSha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(record)));
        json.writeValue(file.toFile(), record);
        Files.writeString(sourceArchive.resolve(".agent/skills/versions/" + published + ".md"), "corrupt approved guide after losing references");
        output.reset();
        var never = new FakeLLMClient(List.of(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(4, cli(never, 4).resumeManagedTask(task), text());
        assertEquals(0, never.callCount()); assertEquals("dev-broken", Files.readString(root.resolve(activeSource)));
    }
    @Test void shouldLoadNewlyPublishedSkillAfterLaterFailedAcceptanceFromInitiallyEmptyLibrary() throws Exception {
        var choice = preparePair(); attest(choice); resetBusiness();
        var round = new java.util.concurrent.atomic.AtomicInteger();
        var offered = new java.util.concurrent.atomic.AtomicReference<Published>();
        var registered = new java.util.concurrent.atomic.AtomicBoolean();
        LLMClient model = (messages, tools) -> {
            try {
                if (round.incrementAndGet() == 1) {
                    assertEquals(0, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
                    String result = id("技能版本");
                    assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, result), text());
                    offered.set(new Published(id("技能"), result));
                    return new LLMResponse("请先独立验收", null, 1, 1);
                }
                if (round.get() == 2) {
                    registered.set(tools.stream().anyMatch(t -> t.name().equals("load_skill")));
                    return calls(call("late", "load_skill", Map.of("id", offered.get().skill(), "version", offered.get().version())),
                            call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed")));
                }
                return new LLMResponse("请验收", null, 1, 1);
            } catch (java.io.IOException failure) { throw failure; }
            catch (Exception failure) { throw new java.io.IOException(failure); }
        };
        assertEquals(0, cli(model, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertTrue(registered.get(), "offered directory must have a registered loader");
        assertTrue(text().contains("type=LOAD,"), text());
    }
    @Test void shouldRepeatUncertainReadForSamePinWithoutDuplicatingReliableGuide() throws Exception {
        preparePublished();
        assertEquals(1, cli(new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published))))), 1)
                .startSkillTask("修复边界", null, control, sourceArchive, false), text());
        String task = id("任务"); assertEquals(0, cli(null, 4).raiseTaskBudget(task, 4), text());
        Path file = root.resolve(".agent/tasks/" + task + "/state.json");
        var record = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        var decision = (com.fasterxml.jackson.databind.node.ObjectNode) record.path("progress").path("decisions").get(0);
        decision.put("roundFinished", false);
        var operation = (com.fasterxml.jackson.databind.node.ObjectNode) decision.path("operations").get(0);
        operation.put("status", "RUNNING"); operation.put("observation", "");
        var messages = (com.fasterxml.jackson.databind.node.ArrayNode) record.path("checkpoint").path("messages");
        messages.remove(messages.size() - 1);
        record.remove("checkpointSha256"); record.put("checkpointSha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(record)));
        json.writeValue(file.toFile(), record); output.reset();
        var resumed = new FakeLLMClient(List.of(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, 4).resumeManagedTask(task), text());
        assertEquals(1, resumed.requests().get(0).stream().filter(m -> "tool".equals(m.role()) && m.content() != null && m.content().contains("SKILL_GUIDE_PRIVATE_MARKER")).count());
        assertTrue(text().contains(published) && text().contains("模型请求: 3/4"), text());
    }
}

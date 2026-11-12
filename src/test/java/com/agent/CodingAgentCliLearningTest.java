package com.agent;

import com.agent.experience.ExperienceLibrary;
import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.session.SessionPaths;
import com.agent.session.SessionStore;
import com.agent.skill.ChatLearning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingAgentCliLearningTest {
    private static final String DRAFT = """
            {"experience":{"conditions":"Python 单文件回显","errorFeatures":"中文回显异常", "recommendation":"核对输入输出字符集", "limitations":"适用性需要当前证据"},
             "skill":{"title":"控制台编码排查","conditions":"终端回显乱码","exclusions":"报告文件解码", "diagnosis":"先比对实际字节", "repair":"按终端编码读写", "verification":"验证中文逐字回显"}}
            """;
    @TempDir Path root;

    @Test
    void shouldLearnFromRestoredPlainChatAndReuseSkillAcrossProjectsAfterExplicitSharing() throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path target = Files.createDirectory(root.resolve("target"));
        Path home = Files.createDirectory(root.resolve("home"));
        Files.writeString(source.resolve("echo.py"), "print(input())\n");
        var sourceSession = sourceSession(source);
        var resumed = SessionStore.start(SessionPaths.defaultDir(source), SessionStore.Mode.RESUME, sourceSession.sessionId());
        var learning = model(DRAFT);
        String output = chat(source, resumed, model(), learning, "/learn 运行 python echo.py，输入中文后逐字回显\nsave\nexit\n", true);

        assertEquals(1, learning.callCount());
        String request = learning.requests().get(0).toString();
        assertTrue(request.contains("修复 echo.py") && request.contains("逐字回显"), request);
        assertFalse(request.contains("private-marker") || request.contains("工具结果"), request);
        assertTrue(output.contains("待确认的验证结果") && output.contains("已保存用户确认的经验和技能"), output);
        var saved = new ChatLearning(source).local("");
        assertEquals(1, saved.size());
        assertEquals(sourceSession.sessionId(), saved.get(0).sessionId());
        assertTrue(new ExperienceLibrary(source).list().isEmpty());
        assertFalse(Files.exists(source.resolve(".agent/tasks")));
        String id = saved.get(0).id().substring(0, 8);

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var local = model("已参考经验", "已参考技能");
            String localOutput = chat(source, null, local, model(), "/experiences\n/experience " + id
                    + "\n/skills\n/skill-info " + id + "\n/use-experience " + id + " 核对回显\n/use-skill " + id + " 编写回显\nexit\n", false);
            assertTrue(localOutput.contains("普通聊天（用户确认）") && localOutput.contains("核对输入输出字符集")
                    && localOutput.contains("控制台编码排查"), localOutput);
            assertTrue(local.requests().get(0).toString().contains("普通聊天经验（用户确认）"));
            assertTrue(local.requests().get(1).toString().contains("普通聊天技能（用户确认）"));
            assertFalse(local.requests().get(1).toString().contains("核对输入输出字符集"));
            assertTrue(chat(target, null, model(), model(), "/skills\nexit\n", false).contains("暂无已发布技能版本"));

            String shared = chat(source, null, model(), model(), "/share-skill " + id + "\nexit\n", false);
            assertTrue(shared.contains("已共享普通聊天技能"), shared);
            var cross = model("已引用共享技能");
            String crossOutput = chat(target, null, cross, model(), "/skills\n/use-skill " + id + " 创建回显\nexit\n", false);
            assertTrue(crossOutput.contains("共享来源") && crossOutput.contains("已引用共享技能"), crossOutput);
            assertFalse(crossOutput.contains("运行 python echo.py"), crossOutput);
            assertEquals(1, cross.callCount());
            String forbidden = chat(target, null, model(), model(), "/unshare-skill " + id + "\nexit\n", false);
            assertTrue(forbidden.contains("在共享技能的原项目执行"), forbidden);
            String revoked = chat(source, null, model(), model(), "/unshare-skill " + id + "\nexit\n", false);
            assertTrue(revoked.contains("已取消共享技能"), revoked);
            String missing = chat(target, null, model(), model(), "/use-skill " + id + " 创建回显\nexit\n", false);
            assertTrue(missing.contains("本轮参考知识不可用"), missing);
            assertEquals(1, new ChatLearning(source).local("").size());
        } finally { System.setProperty("user.home", previousHome); }
    }

    @Test
    void shouldRejectMissingOrSensitiveEvidenceAndOnlySaveAfterConfirmation() throws Exception {
        var session = sourceSession(root);
        var learning = model(DRAFT, DRAFT.replace("控制台编码排查", "secret=private-marker"));
        String output = chat(root, session, model(), learning, "/learn token=private-marker\n"
                + "/learn 已确认回显\n拒绝\n/learn 已确认回显\nexit\n", true);

        assertEquals(2, learning.callCount());
        assertTrue(output.contains("敏感信息") && output.contains("未保存经验和技能"), output);
        assertFalse(output.contains("private-marker"), output);
        assertTrue(new ChatLearning(root).local("").isEmpty());
        var unused = model();
        assertTrue(chat(root, null, unused, unused, "/learn 已确认回显\nexit\n", true).contains("请先在当前聊天描述"));
        assertTrue(chat(root, session, unused, unused, "/learn 已确认回显\nexit\n", false).contains("请在交互终端"));
        assertEquals(0, unused.callCount());
    }

    private static SessionStore sourceSession(Path project) throws Exception {
        var session = SessionStore.start(SessionPaths.defaultDir(project), SessionStore.Mode.NEW, "echo-chat");
        session.appendMessage(Message.user("修复 echo.py 的中文回显"));
        session.appendMessage(Message.user("secret=private-marker"));
        session.appendMessage(Message.tool("old-call", "工具结果"));
        session.appendMessage(Message.assistant("已根据终端编码调整"));
        return session;
    }

    private static FakeLLMClient model(String... replies) {
        return new FakeLLMClient(java.util.Arrays.stream(replies).map(text -> new LLMResponse(text, null, 1, 1)).toList());
    }

    private static String chat(Path project, SessionStore session, FakeLLMClient chat, FakeLLMClient learning,
                               String commands, boolean interactive) {
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new StringReader(commands));
        var cli = new CodingAgentCli(project, chat, false, 4, input, stream, stream, session);
        assertEquals(0, cli.runRepl(null, null, learning, interactive));
        return output.toString(StandardCharsets.UTF_8);
    }
}

package com.agent.roles;

import com.agent.tool.ListFilesTool;
import com.agent.tool.ReadFileTool;
import com.agent.tool.RunCommandTool;
import com.agent.tool.Tool;
import com.agent.tool.WriteFileTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫的裁决策略：只读放行、写入需确认、越界与高危直接拒绝。
 *
 * <p>这些用例完全离线，是「安全策略可回归」的前提：策略一旦被改坏，破坏性行为会立刻暴露。
 */
class GuardRoleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private GuardRole guard;
    private final ReadFileTool readFile = new ReadFileTool();
    private final WriteFileTool writeFile = new WriteFileTool();
    private final ListFilesTool listFiles = new ListFilesTool();
    private final RunCommandTool runCommand = new RunCommandTool();

    @BeforeEach
    void createGuard() {
        // 注意：@TempDir 在实例字段初始化之后才注入，依赖 root 的对象必须放在 @BeforeEach 里创建
        guard = new GuardRole(root);
    }

    private GuardRole.Decision review(Tool tool, String json) throws IOException {
        JsonNode args = json == null ? null : MAPPER.readTree(json);
        return guard.review(tool, args);
    }

    @Test
    void shouldAllowReadOnlyTools() throws Exception {
        assertEquals(GuardRole.Outcome.ALLOW,
                review(readFile, "{\"path\":\"pom.xml\"}").outcome());
        assertEquals(GuardRole.Outcome.ALLOW,
                review(listFiles, "{\"path\":\".\"}").outcome());
    }

    @Test
    void shouldRequireConfirmationForWritesInsideProject() throws Exception {
        GuardRole.Decision decision = review(writeFile, "{\"path\":\"notes/todo.md\",\"content\":\"x\"}");

        assertEquals(GuardRole.Outcome.CONFIRM, decision.outcome());
        assertTrue(decision.allowed());
        assertTrue(decision.needsConfirmation());
    }

    @Test
    void shouldDenyPathEscapingProjectRoot() throws Exception {
        GuardRole.Decision decision = review(writeFile, "{\"path\":\"../outside.txt\",\"content\":\"x\"}");

        assertEquals(GuardRole.Outcome.DENY, decision.outcome());
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("越出项目根目录"), decision.reason());
    }

    @Test
    void shouldDenyAbsolutePathOutsideProjectRoot() throws Exception {
        Path outside = root.getParent().resolve("outside.txt");

        GuardRole.Decision decision = review(writeFile,
                "{\"path\":\"" + outside.toString().replace('\\', '/') + "\",\"content\":\"x\"}");

        assertEquals(GuardRole.Outcome.DENY, decision.outcome());
    }

    @Test
    void shouldDenyWritingEnvFileOrGitDirectory() throws Exception {
        assertEquals(GuardRole.Outcome.DENY, review(writeFile, "{\"path\":\".env\",\"content\":\"x\"}").outcome());
        assertEquals(GuardRole.Outcome.DENY,
                review(writeFile, "{\"path\":\".git/config\",\"content\":\"x\"}").outcome());
        assertEquals(GuardRole.Outcome.DENY,
                review(writeFile, "{\"path\":\"certs/server.key\",\"content\":\"x\"}").outcome());
    }

    @Test
    void shouldDenyDestructiveCommands() throws Exception {
        assertDenied("rm -rf /");
        assertDenied("git reset --hard HEAD~3");
        assertDenied("del /f /q *.java");
        assertDenied("Remove-Item -Recurse -Force src");
        assertDenied("format C:");
    }

    @Test
    void shouldDenyCommandTouchingPathOutsideRoot() throws Exception {
        assertDenied("type C:\\Windows\\win.ini");
        assertDenied("cat /etc/passwd");
    }

    @Test
    void shouldDenyParentDirectoryTraversalInCommand() throws Exception {
        assertDenied("cd ../other-project && git status");
    }

    @Test
    void shouldRequireConfirmationForOrdinaryCommands() throws Exception {
        GuardRole.Decision decision = review(runCommand, "{\"command\":\"mvn -q -DskipTests compile\"}");

        assertEquals(GuardRole.Outcome.CONFIRM, decision.outcome());
        assertTrue(decision.reason().contains("mvn"));
    }

    @Test
    void shouldNotTreatCmdSwitchesAsPaths() throws Exception {
        // /d /c 是 cmd.exe 的开关，不是绝对路径，不能误判成「越界路径」
        GuardRole.Decision decision = review(runCommand, "{\"command\":\"dir /d /c\"}");

        assertEquals(GuardRole.Outcome.CONFIRM, decision.outcome());
    }

    @Test
    void shouldAllowToolCallWithMissingArgumentsToReachTheTool() throws Exception {
        // 参数缺失不是安全问题，应交给工具报「缺少参数」，而不是被守卫拦成安全事件
        assertEquals(GuardRole.Outcome.ALLOW, review(readFile, "{}").outcome());
    }

    @Test
    void shouldExposeProjectRoot() {
        assertEquals(root.toAbsolutePath().normalize(), guard.root());
    }

    private void assertDenied(String command) throws Exception {
        String json = "{\"command\":\"" + command.replace("\\", "\\\\") + "\"}";
        GuardRole.Decision decision = review(runCommand, json);

        assertEquals(GuardRole.Outcome.DENY, decision.outcome(), "应拒绝命令: " + command);
        assertFalse(decision.allowed());
    }
}

package com.agent.roles;

import com.agent.command.CommandRunner;
import com.agent.command.ProcessCommandRunner;
import com.agent.llm.LLMClient;
import com.agent.tool.IgnoreRules;
import com.agent.tool.ListFilesTool;
import com.agent.tool.ReadFileTool;
import com.agent.tool.RunCommandTool;
import com.agent.tool.SearchCodeTool;
import com.agent.tool.Tool;
import com.agent.tool.WriteFileTool;
import com.agent.tool.ReplaceTextTool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 角色三：<b>注册员</b>。
 *
 * <p>它维护「这个 Agent 会哪些工具」这本花名册，对外提供两种查询：
 *
 * <ul>
 *   <li>{@link #toolDefinitions()}：给 LLM 看的工具清单（名字 + 描述 + JSON Schema）；</li>
 *   <li>{@link #find(String)}：给控制员用的按名查找。</li>
 * </ul>
 *
 * <p>注册员只做登记与检索，<b>不执行</b>工具（那是执行员），也<b>不做</b>安全检查（那是守卫）。
 * 名字重复会直接抛异常，避免「同一个名字指向两个工具」这种极难排查的问题。
 */
public class RegistrarRole {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 注册标准工具集：读文件、写文件、列目录、搜代码、执行命令。 */
    public static RegistrarRole standard(Path root) {
        return standard(root, new ProcessCommandRunner());
    }

    /** 让装配点选择进程实现，注册员只负责把同一入口交给命令工具。 */
    public static RegistrarRole standard(Path root, CommandRunner commandRunner) {
        return standard(root, commandRunner, (path, version) -> { });
    }

    public static RegistrarRole standard(Path root, CommandRunner commandRunner,
                                         java.util.function.BiConsumer<Path, String> checkVersion) {
        return standard(root, commandRunner, checkVersion, path -> true);
    }

    /** 只在管理任务装配时注入可见性，避免改变普通聊天的工具行为。 */
    public static RegistrarRole standard(Path root, CommandRunner commandRunner,
                                         java.util.function.BiConsumer<Path, String> checkVersion,
                                         java.util.function.Predicate<Path> isVisible) {
        IgnoreRules rules = ignoreRules(root);
        return new RegistrarRole()
                .register(new ReadFileTool(isVisible))
                .register(new WriteFileTool())
                .register(new ReplaceTextTool(checkVersion))
                .register(new ListFilesTool(rules, isVisible))
                .register(new SearchCodeTool(rules, isVisible))
                .register(new RunCommandTool(commandRunner));
    }

    private static IgnoreRules ignoreRules(Path root) {
        try {
            return IgnoreRules.load(root.resolve(".agentignore"));
        } catch (IOException unreadable) {
            return IgnoreRules.defaults();
        }
    }

    /** 登记一个工具；名字为空或重复都会抛异常，返回自身以便链式调用。 */
    public RegistrarRole register(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("工具必须有非空的名字");
        }
        if (tools.containsKey(tool.name())) {
            throw new IllegalStateException("工具名重复注册: " + tool.name());
        }
        tools.put(tool.name(), tool);
        return this;
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Tool> tools() {
        return List.copyOf(tools.values());
    }

    public Set<String> names() {
        return Set.copyOf(tools.keySet());
    }

    /** 转成 LLM 协议需要的工具定义列表。 */
    public List<LLMClient.ToolDef> toolDefinitions() {
        return tools.values().stream()
                .map(tool -> new LLMClient.ToolDef(
                        tool.name(), tool.description(), tool.parameters()))
                .toList();
    }
}

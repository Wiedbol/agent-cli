package com.agent.tool;

import com.agent.llm.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表：把 Lab02 中三个「签名各不相同」的文件工具适配成 Tool Calling 协议需要的统一形状。
 *
 * <pre>
 * 工具名 + JSON 参数 ──► ToolRegistry ──► Lab02 Tool ──► 字符串结果
 * </pre>
 *
 * <p>职责有三：向模型描述工具（{@link #getToolDefinitions()}）、按名字查表、解析参数并执行。
 * 它<b>不</b>决定下一步调用哪个工具 —— 那是模型的决策。
 *
 * <p>这是 Lab04 前的过渡设计，有两点刻意保留的局限：
 *
 * <ul>
 *   <li>参数被压成 {@code Map<String,String>}，数字 / 布尔 / 数组类型信息会丢失
 *       （当前三个文件工具只用到字符串与整数，够用）；</li>
 *   <li>Schema 由 {@code createParameters} 手工拼装，而不是由统一的 {@code Tool} 接口自描述。</li>
 * </ul>
 *
 * <p>与 Lab02 行为一致的两条约定：工具失败返回<b>错误字符串</b>而不是抛异常（错误也是
 * Observation，模型可以据此改参数或向用户解释）；所有路径都限制在构造时给定的项目根目录内。
 */
public class ToolRegistry {

    /** 单个工具结果回传给模型的最大字符数，避免一次读取把上下文撑爆（选做任务 1）。 */
    public static final int DEFAULT_MAX_RESULT_CHARS = 8000;

    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path root;
    private final int maxResultChars;

    /** 一次注册项：协议描述 + 真正的执行逻辑。 */
    public record ToolEntry(
            String name,
            String description,
            JsonNode parameters,
            ToolHandler handler) {
    }

    /**
     * 工具执行逻辑：入参是已解析的参数表，出参是回传给模型的文本。
     *
     * <p>允许抛 {@link IOException}（Lab02 的文件工具都是这么声明的），由
     * {@link #execute(String, String)} 统一兜底成错误 Observation。
     */
    public interface ToolHandler {
        String execute(Map<String, String> args) throws IOException;
    }

    /** 一个工具参数的声明，用来生成 JSON Schema。 */
    public record Param(String name, String type, String description, boolean required) {
    }

    /** 以当前工作目录为根目录、使用默认结果长度上限。 */
    public ToolRegistry() {
        this(Path.of("").toAbsolutePath(), DEFAULT_MAX_RESULT_CHARS);
    }

    /** 以指定项目根目录构造（所有工具路径都被限制在该目录内）。 */
    public ToolRegistry(Path root) {
        this(root, DEFAULT_MAX_RESULT_CHARS);
    }

    public ToolRegistry(Path root, int maxResultChars) {
        if (root == null) {
            throw new IllegalArgumentException("root 不能为空");
        }
        if (maxResultChars <= 0) {
            throw new IllegalArgumentException("maxResultChars 必须为正数: " + maxResultChars);
        }
        this.root = root.toAbsolutePath().normalize();
        this.maxResultChars = maxResultChars;
        registerFileTools();
    }

    /** 工具允许访问的根目录。 */
    public Path root() {
        return root;
    }

    /** 已注册的工具名（按注册顺序）。 */
    public Set<String> toolNames() {
        return Set.copyOf(tools.keySet());
    }

    /** 发给模型的工具定义列表。 */
    public List<LLMClient.ToolDef> getToolDefinitions() {
        return tools.values().stream()
                .map(tool -> new LLMClient.ToolDef(
                        tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    /**
     * 执行一次工具调用。
     *
     * <p>任何失败（未知工具、参数不是合法 JSON、路径越界、底层工具抛异常）都转成错误字符串返回，
     * 让 Agent 可以把错误当作 Observation 继续回传给模型，而不是直接崩溃。
     *
     * @param toolName      模型给出的工具名
     * @param argumentsJson 模型给出的参数 JSON 字符串，允许为 {@code null} 或空白
     */
    public String execute(String toolName, String argumentsJson) {
        ToolEntry tool = tools.get(toolName);
        if (tool == null) {
            return "未知工具: " + toolName + "（可用工具: " + String.join(", ", tools.keySet()) + "）";
        }

        Map<String, String> args;
        try {
            args = parseArguments(argumentsJson);
        } catch (IOException | RuntimeException malformed) {
            return "工具参数不是合法 JSON 对象: " + argumentsJson;
        }

        String result;
        try {
            result = tool.handler().execute(args);
        } catch (IOException | RuntimeException failed) {
            result = "执行工具失败: " + failed.getMessage();
        }
        return truncate(result);
    }

    /** 解析参数 JSON；空串 / {@code null} 视为「没有参数」。 */
    private Map<String, String> parseArguments(String argumentsJson) throws IOException {
        Map<String, String> args = new LinkedHashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return args;
        }
        JsonNode node = mapper.readTree(argumentsJson);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return args;
        }
        if (!node.isObject()) {
            throw new IOException("参数必须是 JSON 对象: " + argumentsJson);
        }
        node.fieldNames().forEachRemaining(name -> args.put(name, node.path(name).asText()));
        return args;
    }

    /** 超过上限就截断，并明确告知模型「内容不完整」。 */
    private String truncate(String result) {
        if (result == null) {
            return "";
        }
        if (result.length() <= maxResultChars) {
            return result;
        }
        return result.substring(0, maxResultChars)
                + "\n...(结果已截断，原长度 " + result.length() + " 字符)";
    }

    private JsonNode createParameters(Param... params) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        root.put("additionalProperties", false);

        for (Param param : params) {
            ObjectNode property = properties.putObject(param.name());
            property.put("type", param.type());
            property.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }
        return root;
    }

    /** 解析工具路径：相对路径按根目录展开，并拒绝越出根目录的路径（挑战任务 3）。 */
    private Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path path = Path.of(rawPath);
        Path resolved = (path.isAbsolute() ? path : root.resolve(path)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("只允许访问项目根目录内的路径: " + rawPath);
        }
        return resolved;
    }

    private static int intArg(Map<String, String> args, String name, int defaultValue) {
        String raw = args.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    private static long longArg(Map<String, String> args, String name, long defaultValue) {
        String raw = args.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }

    private void registerFileTools() {
        var lister = listFilesTool();
        var reader = new ReadFileTool();
        var searcher = searchCodeTool();

        tools.put("list_files", new ToolEntry(
                "list_files",
                "递归列出指定目录下的文件，返回相对项目根目录的路径列表",
                createParameters(
                        new Param("path", "string", "目录路径（相对项目根目录）", true),
                        new Param("maxDepth", "integer", "最大递归层数，1 表示只看直接子项", false)),
                args -> {
                    Path dir = resolve(args.get("path"));
                    int maxDepth = intArg(args, "maxDepth", Integer.MAX_VALUE);
                    List<String> files = lister.listFiles(dir, maxDepth);
                    if (files.isEmpty()) {
                        return "目录下没有文件: " + args.get("path");
                    }
                    return "文件列表（共 " + files.size() + " 个）:\n" + String.join("\n", files);
                }));

        tools.put("read_file", new ToolEntry(
                "read_file",
                "读取指定 UTF-8 文本文件的内容，可用 offset/limit 分页读取大文件",
                createParameters(
                        new Param("path", "string", "文件路径（相对项目根目录）", true),
                        new Param("offset", "integer", "起始行号（0 起），省略表示从头读", false),
                        new Param("limit", "integer", "最多读取的行数，省略表示读到结尾", false)),
                args -> {
                    Path file = resolve(args.get("path"));
                    boolean paged = (args.containsKey("offset") && !args.get("offset").isBlank())
                            || (args.containsKey("limit") && !args.get("limit").isBlank());
                    String content = paged
                            ? reader.readFile(file, longArg(args, "offset", 0), longArg(args, "limit", 200))
                            : reader.readFile(file);
                    return "文件内容:\n" + content;
                }));

        tools.put("search_code", new ToolEntry(
                "search_code",
                "在指定目录中搜索包含关键字的代码行，返回 文件:行号: 内容",
                createParameters(
                        new Param("keyword", "string", "要搜索的关键字", true),
                        new Param("path", "string", "搜索起始目录（相对项目根目录）", true)),
                args -> {
                    Path dir = resolve(args.get("path"));
                    List<SearchCodeTool.Match> matches = searcher.searchCode(args.get("keyword"), dir);
                    if (matches.isEmpty()) {
                        return "没有找到包含「" + args.get("keyword") + "」的内容";
                    }
                    StringBuilder result = new StringBuilder("搜索结果（共 " + matches.size() + " 条）:\n");
                    for (SearchCodeTool.Match match : matches) {
                        result.append(match.file()).append(':')
                                .append(match.line()).append(": ")
                                .append(match.content()).append('\n');
                    }
                    return result.toString();
                }));
    }

    private ListFilesTool listFilesTool() {
        try {
            return ListFilesTool.fromConfig(root.resolve(".agentignore"));
        } catch (IOException unreadable) {
            return new ListFilesTool(IgnoreRules.defaults());
        }
    }

    private SearchCodeTool searchCodeTool() {
        try {
            return SearchCodeTool.fromConfig(root.resolve(".agentignore"));
        } catch (IOException unreadable) {
            return new SearchCodeTool(IgnoreRules.defaults());
        }
    }
}

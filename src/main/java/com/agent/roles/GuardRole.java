package com.agent.roles;

import com.agent.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 角色四：<b>守卫</b>。
 *
 * <p>守卫是「决定某个操作能不能做」的唯一裁决者，输出三种结论：
 *
 * <ul>
 *   <li>{@link Outcome#ALLOW}：放行（只读操作）；</li>
 *   <li>{@link Outcome#CONFIRM}：放行但必须由<b>用户</b>确认（写文件、执行命令）；</li>
 *   <li>{@link Outcome#DENY}：直接拒绝（路径越界、受保护文件、高危命令）。</li>
 * </ul>
 *
 * <p>检查分三层：
 *
 * <ol>
 *   <li><b>路径沙箱</b>：工具声明的每个路径参数都可能指向项目外，必须归一化后仍在根目录内；</li>
 *   <li><b>受保护路径</b>：{@code .git/}、{@code .env}、密钥文件即使位于项目内也禁止写入；</li>
 *   <li><b>命令审查</b>：高危片段黑名单、上级目录引用、指向项目外的绝对路径。</li>
 * </ol>
 *
 * <p><b>诚实的边界说明</b>：命令审查是「黑名单 + 人工确认」，它能挡住常见误操作与明显破坏性指令，
 * 但<b>不是</b>完备沙箱 —— 真正隔离需要容器/虚拟机/受限用户。这里的设计价值在于：
 * 把「安全判断」收敛到一个可测试、可审计的类里，而不是散落在提示词里靠模型自觉。
 */
public class GuardRole {

    /** 裁决结论。 */
    public enum Outcome {
        ALLOW,
        CONFIRM,
        DENY
    }

    /**
     * 一条裁决。
     *
     * @param outcome 结论
     * @param reason  给用户与模型看的原因（会进入 Observation）
     */
    public record Decision(Outcome outcome, String reason) {

        public static Decision allow(String reason) {
            return new Decision(Outcome.ALLOW, reason);
        }

        public static Decision confirm(String reason) {
            return new Decision(Outcome.CONFIRM, reason);
        }

        public static Decision deny(String reason) {
            return new Decision(Outcome.DENY, reason);
        }

        public boolean allowed() {
            return outcome != Outcome.DENY;
        }

        public boolean needsConfirmation() {
            return outcome == Outcome.CONFIRM;
        }
    }

    /** 明确禁止的命令片段（统一小写后做子串匹配）。 */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "rm -rf", "rm -fr", "rmdir /s", "del /f", "del /s", "del /q", "erase /f",
            "format ", "mkfs", "diskpart", "fdisk", "shutdown", "reboot", "halt",
            "reg delete", "reg add", "takeown", "icacls", "bcdedit", "cipher /w",
            "net user", "netsh ", "sc delete", "taskkill /f",
            "remove-item -recurse", "remove-item -force", "stop-computer", "restart-computer",
            "git reset --hard", "git clean -fd", "git clean -fdx", "git checkout --",
            ":(){", "> /dev/sd", "curl | bash", "curl | sh", "wget -o- | sh",
            "iwr | iex", "invoke-expression", "set-executionpolicy unrestricted");

    /** 项目内也不允许写入的路径前缀（相对根目录，使用 {@code /} 分隔）。 */
    private static final List<String> PROTECTED_PREFIXES = List.of(".git/", ".env");

    /** 项目内也不允许写入的密钥类后缀。 */
    private static final List<String> PROTECTED_SUFFIXES = List.of(".key", ".pem", ".pfx", ".p12", ".keystore");

    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("[A-Za-z]:[\\\\/][^\\s\"']*");
    private static final Pattern UNIX_LIKE_ABSOLUTE = Pattern.compile("(?<![\\w.:/-])/[A-Za-z0-9_.-]+(?:/[^\\s\"']*)?");
    private static final Pattern SINGLE_LETTER_SWITCH = Pattern.compile("(?i)/[a-z]");

    private final Path root;
    private final java.util.function.Predicate<Path> writeProtected;

    public GuardRole(Path root) {
        this(root, path -> false);
    }

    /** 任务可追加只读输入策略，普通聊天仍使用原有守卫规则。 */
    public GuardRole(Path root, java.util.function.Predicate<Path> writeProtected) {
        this.root = root.toAbsolutePath().normalize();
        this.writeProtected = writeProtected;
    }

    public Path root() {
        return root;
    }

    /**
     * 对一个工具调用做安全裁决。
     *
     * @param tool      注册员找到的工具
     * @param arguments 模型给出的参数（可为 {@code null}）
     */
    public Decision review(Tool tool, JsonNode arguments) {
        Decision pathDecision = reviewPaths(tool, arguments);
        if (pathDecision != null) {
            return pathDecision;
        }

        return switch (tool.risk()) {
            case READ -> Decision.allow("只读操作: " + tool.name());
            case WRITE -> Decision.confirm("写入操作: " + tool.name() + " 将修改项目文件");
            case EXECUTE -> reviewCommand(arguments);
        };
    }

    /** 返回 {@code null} 表示路径检查通过（继续走风险等级判断）。 */
    private Decision reviewPaths(Tool tool, JsonNode arguments) {
        for (String key : tool.pathArguments()) {
            JsonNode node = arguments == null ? null : arguments.path(key);
            if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
                continue; // 缺参数交给工具自己报错，守卫不越俎代庖
            }
            String raw = node.asText();
            Path resolved;
            try {
                resolved = resolve(raw);
            } catch (RuntimeException invalid) {
                return Decision.deny("路径不合法: " + raw);
            }
            if (!resolved.startsWith(root)) {
                return Decision.deny("路径越出项目根目录: " + raw);
            }
            String relative = root.relativize(resolved).toString().replace('\\', '/');
            if (tool.risk() == Tool.Risk.WRITE && writeProtected.test(resolved)) {
                return Decision.deny("任务验收输入或任务元数据受保护，修改需明确重新约定: " + relative);
            }
            if (tool.risk() != Tool.Risk.READ && isProtected(relative)) {
                return Decision.deny("受保护路径不允许写入: " + relative);
            }
        }
        return null;
    }

    private Decision reviewCommand(JsonNode arguments) {
        String command = arguments == null ? "" : arguments.path("command").asText("").trim();
        if (command.isEmpty()) {
            return Decision.allow("缺少 command 参数，交由工具报错");
        }
        String lower = command.toLowerCase(Locale.ROOT);
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return Decision.deny("命令包含高危片段 «" + fragment + "»：" + command);
            }
        }
        if (command.contains("../") || command.contains("..\\")) {
            return Decision.deny("命令包含上级目录引用（..），可能越出项目根目录：" + command);
        }
        String outside = findOutsideAbsolutePath(command);
        if (outside != null) {
            return Decision.deny("命令引用了项目根目录之外的绝对路径 " + outside + "：" + command);
        }
        return Decision.confirm("将在项目根目录执行命令: " + command);
    }

    private Path resolve(String raw) {
        Path path = Path.of(raw);
        return (path.isAbsolute() ? path : root.resolve(path)).normalize();
    }

    private boolean isProtected(String relativePath) {
        String relative = relativePath.toLowerCase(Locale.ROOT);
        for (String prefix : PROTECTED_PREFIXES) {
            if (relative.equals(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix)
                    || relative.startsWith(prefix)) {
                return true;
            }
        }
        for (String suffix : PROTECTED_SUFFIXES) {
            if (relative.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 找出命令中指向项目根目录之外的绝对路径；没有则返回 {@code null}。 */
    private String findOutsideAbsolutePath(String command) {
        Matcher windows = WINDOWS_ABSOLUTE.matcher(command);
        while (windows.find()) {
            String candidate = windows.group();
            if (!resolve(candidate).startsWith(root)) {
                return candidate;
            }
        }

        Matcher unixLike = UNIX_LIKE_ABSOLUTE.matcher(command);
        while (unixLike.find()) {
            String candidate = unixLike.group();
            // 排除 cmd 风格的开关（/d、/c、/f …）：它们不是路径
            if (SINGLE_LETTER_SWITCH.matcher(candidate).matches()) {
                continue;
            }
            if (!resolve(candidate).startsWith(root)) {
                return candidate;
            }
        }
        return null;
    }
}

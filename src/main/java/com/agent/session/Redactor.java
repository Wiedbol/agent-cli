package com.agent.session;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感内容过滤器（纯函数，便于单独测试）。
 *
 * <p>按需求（K② / R②）实现三条规则，命中任意一条就把<b>整条内容</b>替换成占位符：
 *
 * <ol>
 *   <li><b>敏感路径</b>：工具参数里的 {@code path} 指向 {@code .env}、{@code *.key}、{@code *.pem}、
 *       {@code *.p12}、{@code *.keystore}、{@code id_rsa*} 时，本次工具结果整体过滤；</li>
 *   <li><b>键值样式</b>：内容里出现 {@code password|passwd|secret|token|api_key|apikey|private_key}
 *       且形如「键 = 值」或「键: 值」；</li>
 *   <li><b>疑似密钥串</b>：形如 {@code sk-} 开头且长度 ≥ 20 的片段。</li>
 * </ol>
 *
 * <p><b>只影响落盘</b>：过滤发生在写文件之前，内存中的历史仍是原文（R② 的选择），
 * 因此模型后续推理不受影响，代价是复盘时看到的是占位符。
 */
public final class Redactor {

    /** 命中后替换成的文本。 */
    public static final String PLACEHOLDER = "[已过滤：疑似敏感内容]";

    /** 敏感文件名（规则 1）。 */
    private static final Pattern SENSITIVE_PATH = Pattern.compile(
            "(?i)(^|[/\\\\])(\\.env(\\.[\\w.-]+)?|[\\w.-]*\\.(key|pem|p12|keystore)|id_rsa[a-z0-9_.-]*)($|[/\\\\])");

    /** 工具参数里的 path 字段（只做轻量提取，避免为一个字段引入完整 JSON 解析）。 */
    private static final Pattern PATH_ARGUMENT = Pattern.compile("\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 键值样式的敏感键名（规则 2）。 */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?<![A-Za-z])(password|passwd|secret|token|api[_-]?key|apikey|private[_-]?key)\\s*[:=]\\s*\\S+");

    /** 疑似密钥串（规则 3）。 */
    private static final Pattern KEY_LIKE = Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}");

    /** 一次过滤的结果。 */
    public record Result(String text, boolean redacted) {
    }

    private Redactor() {
    }

    /** 过滤普通内容（只应用规则 2、3）。 */
    public static Result redact(String text) {
        return redact(text, null);
    }

    /**
     * 过滤内容。
     *
     * @param text              待过滤文本
     * @param toolArgumentsJson 工具参数 JSON（可为 {@code null}）；用于规则 1 的路径判断
     */
    public static Result redact(String text, String toolArgumentsJson) {
        String safeText = text == null ? "" : text;
        if (touchesSensitivePath(toolArgumentsJson)
                || SECRET_ASSIGNMENT.matcher(safeText).find()
                || KEY_LIKE.matcher(safeText).find()) {
            return new Result(PLACEHOLDER, true);
        }
        return new Result(safeText, false);
    }

    /** 工具参数的 path 是否指向敏感文件。 */
    public static boolean touchesSensitivePath(String toolArgumentsJson) {
        if (toolArgumentsJson == null || toolArgumentsJson.isBlank()) {
            return false;
        }
        Matcher matcher = PATH_ARGUMENT.matcher(toolArgumentsJson);
        while (matcher.find()) {
            String path = matcher.group(1).replace("\\\\", "/").replace('\\', '/');
            if (SENSITIVE_PATH.matcher(path).find()) {
                return true;
            }
        }
        return false;
    }
}

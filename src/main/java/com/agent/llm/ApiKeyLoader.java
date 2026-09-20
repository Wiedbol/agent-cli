package com.agent.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * API Key 加载器：优先读项目根目录的 {@code .env}，其次读环境变量。
 *
 * <p>把加载逻辑从 {@code Main} 里抽出来有两个原因：一是 Key 的读取规则（BOM、引号、注释、
 * 优先级）值得单独测试；二是 {@code Main} 只应该负责「接线」，不应该顺带解析配置文件。
 *
 * <p><b>安全约定</b>：本类只返回值，不打印、不记录 Key；{@code .env} 必须留在
 * {@code .gitignore} 中，绝不能提交。
 */
public final class ApiKeyLoader {

    /** DeepSeek 平台使用的环境变量名。 */
    public static final String DEEPSEEK_KEY = "DEEPSEEK_API_KEY";

    /** 默认的配置文件相对路径（相对于启动时的工作目录）。 */
    public static final String DEFAULT_ENV_FILE = ".env";

    private ApiKeyLoader() {
    }

    /** 按默认约定加载 DeepSeek Key：{@code .env} 优先，环境变量兜底。 */
    public static String loadDeepSeekKey() {
        return load(Path.of(DEFAULT_ENV_FILE), DEEPSEEK_KEY, System::getenv);
    }

    /**
     * 通用加载逻辑（便于测试注入假的文件路径与环境变量查询函数）。
     *
     * @param envFile  {@code .env} 路径，可为 {@code null} 或不存在
     * @param keyName  键名，例如 {@code DEEPSEEK_API_KEY}
     * @param envLookup 环境变量查询函数，可为 {@code null}
     * @return Key；两处都没有时返回 {@code null}
     */
    public static String load(Path envFile, String keyName, Function<String, String> envLookup) {
        String fromFile = loadFromFile(envFile, keyName);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        if (envLookup == null || keyName == null) {
            return null;
        }
        String fromEnv = envLookup.apply(keyName);
        return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.trim();
    }

    /** 从 {@code .env} 的文本内容中解析 Key（去掉 BOM、支持引号、跳过注释）。 */
    static String parse(String content, String keyName) {
        if (content == null || keyName == null) {
            return null;
        }
        List<String> lines = List.of(content.split("\\R", -1));
        for (String rawLine : lines) {
            String line = rawLine.replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (keyName.equals(line.substring(0, equals).trim())) {
                return unquote(line.substring(equals + 1).trim());
            }
        }
        return null;
    }

    private static String loadFromFile(Path envFile, String keyName) {
        if (envFile == null || keyName == null || !Files.isRegularFile(envFile)) {
            return null;
        }
        try {
            return parse(Files.readString(envFile, StandardCharsets.UTF_8), keyName);
        } catch (IOException unreadable) {
            // 配置文件读不了就当作「没有配置」，由调用方给出提示；不把异常当成密钥错误。
            return null;
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            if (singleQuoted || doubleQuoted) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}

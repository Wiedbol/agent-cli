package com.agent.session;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 会话文件的路径与命名规则。
 *
 * <p>默认写进项目内的 {@code <root>/.agent/sessions/}（C③），也可以用
 * {@code --session-dir user} 切到用户目录；会话默认用时间戳命名，也可以用
 * {@code --session <名字>} 指定（O③）。
 *
 * <p>单个会话由一到多个分片组成：{@code name.jsonl} → {@code name.part2.jsonl} → …
 * 超过 5 MB 就开新分片，恢复时按序号升序全部读回（R①）。
 */
public final class SessionPaths {

    /** 项目内默认目录名。 */
    public static final String DEFAULT_DIR = ".agent/sessions";

    /** {@code --session-dir user} 对应的用户目录。 */
    public static final String USER_DIR = ".agent-cli/sessions";

    /** 单分片大小上限：5 MB（R①）。 */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private SessionPaths() {
    }

    /** 时间戳会话名，例如 {@code 2026-10-16-153012}。 */
    public static String timestampName() {
        return LocalDateTime.now().format(STAMP);
    }

    /** 项目内的默认会话目录。 */
    public static Path defaultDir(Path root) {
        return root.resolve(DEFAULT_DIR).toAbsolutePath().normalize();
    }

    /** 用户目录下的会话目录。 */
    public static Path userDir() {
        return Path.of(System.getProperty("user.home", "."), USER_DIR).toAbsolutePath().normalize();
    }

    /** 解析 {@code --session-dir} 的取值：{@code user} 走用户目录，其余按路径处理。 */
    public static Path resolveDir(Path root, String optionValue) {
        if (optionValue == null || optionValue.isBlank()) {
            return defaultDir(root);
        }
        if ("user".equalsIgnoreCase(optionValue.trim())) {
            return userDir();
        }
        return Path.of(optionValue.trim()).toAbsolutePath().normalize();
    }

    /** 第 {@code part} 个分片的文件名（part=1 时就是 {@code name.jsonl}）。 */
    public static String shardFileName(String sessionId, int part) {
        return part <= 1 ? sessionId + ".jsonl" : sessionId + ".part" + part + ".jsonl";
    }

    public static Path shard(Path dir, String sessionId, int part) {
        return dir.resolve(shardFileName(sessionId, part));
    }

    /** 从分片文件名还原会话名；不是会话文件时返回 {@code null}。 */
    public static String sessionIdOf(String fileName) {
        if (fileName == null || !fileName.endsWith(".jsonl")) {
            return null;
        }
        String base = fileName.substring(0, fileName.length() - ".jsonl".length());
        int partIndex = base.lastIndexOf(".part");
        return partIndex < 0 ? base : base.substring(0, partIndex);
    }
}

package com.agent.task;

import java.nio.charset.StandardCharsets;

/** 只去掉时间和临时位置噪声，保留测试身份、错误类型和期望值差异。 */
final class CoreError {
    private CoreError() { }

    static String normalize(String text) {
        return text.replaceAll("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?", "<time>")
                .replaceAll("\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b", "<time>")
                .replaceAll("(?i)[a-z]:[\\\\/](?:[^\\s<>\"']*[\\\\/])?(?:temp|tmp)[\\\\/][^\\s<>\"']*", "<tmp>")
                .replaceAll("/(?:private/|var/)?tmp/[^\\s<>\"']*", "<tmp>")
                .replaceAll("(?i)(?:time elapsed|duration)[:= ]+[0-9.]+\\s*(?:ms|s|sec)", "<duration>")
                .replaceAll("\\s+", " ").trim();
    }

    static String key(String text) {
        return WorkspaceSnapshot.hash(normalize(text).getBytes(StandardCharsets.UTF_8));
    }
}

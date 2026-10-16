package com.agent.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把 JSONL 会话导出成人类可读的 markdown（需求 N①）。
 *
 * <p>用途是复盘：JSONL 适合机器读，但「当时到底发生了什么」用 markdown 看更直观。
 * 导出内容包含每轮的时间/耗时/token、每次工具调用的参数与结果，以及被过滤条目的标记。
 */
public final class SessionExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private SessionExporter() {
    }

    /** 渲染指定会话；{@code id} 可以是会话名或 {@code latest}。 */
    public static String toMarkdown(Path dir, String id) throws IOException {
        String sessionId = "latest".equalsIgnoreCase(id)
                ? SessionStore.latest(dir).orElseThrow(() -> new IOException("没有任何会话可以导出"))
                : id;
        if (!SessionStore.exists(dir, sessionId)) {
            throw new IOException("会话不存在: " + sessionId);
        }

        StringBuilder markdown = new StringBuilder("# 会话 " + sessionId + "\n\n");
        for (Path shard : SessionStore.shards(dir, sessionId)) {
            for (String line : Files.readAllLines(shard, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    render(markdown, MAPPER.readTree(line));
                } catch (Exception corrupt) {
                    markdown.append("> ⚠️ 跳过一行损坏记录\n\n");
                }
            }
        }
        return markdown.toString();
    }

    private static void render(StringBuilder markdown, JsonNode node) {
        String type = node.path("type").asText();
        String at = node.path("at").asText("");
        switch (type) {
            case "meta" -> markdown.append("> ").append(at)
                    .append(node.path("resumed").asBoolean(false) ? " 继续记录（第 "
                            + node.path("part").asInt(1) + " 个分片）" : " 会话开始")
                    .append("\n\n");
            case "turn" -> markdown.append("###### 第 ").append(node.path("step").asInt())
                    .append(" 轮 · ").append(node.path("elapsedMillis").asLong()).append(" ms")
                    .append(" · token 输入 ").append(node.path("promptTokens").asInt())
                    .append(" / 输出 ").append(node.path("completionTokens").asInt())
                    .append(" · 工具: ").append(node.path("tools").asText("-"))
                    .append("\n\n");
            case "message" -> renderMessage(markdown, node);
            default -> {
                // 未来新增事件类型时忽略，保证旧版本的导出器不会崩
            }
        }
    }

    private static void renderMessage(StringBuilder markdown, JsonNode node) {
        String role = node.path("role").asText();
        String content = node.path("content").asText("");
        boolean redacted = node.path("redacted").asBoolean(false);

        switch (role) {
            case "user" -> markdown.append("## 👤 用户\n\n").append(content).append("\n\n");
            case "assistant" -> {
                markdown.append("## 🤖 Agent\n\n");
                if (!content.isBlank()) {
                    markdown.append(content).append("\n\n");
                }
                for (JsonNode call : node.path("toolCalls")) {
                    markdown.append("**调用工具**：`").append(call.path("function").path("name").asText())
                            .append("`\n\n```json\n")
                            .append(call.path("function").path("arguments").asText())
                            .append("\n```\n\n");
                }
            }
            case "tool" -> {
                markdown.append("## 🔧 工具结果\n\n");
                markdown.append("```text\n").append(content).append("\n```\n\n");
                if (redacted) {
                    markdown.append("> ⚠️ 本条内容在落盘时被敏感过滤替换\n\n");
                }
            }
            default -> markdown.append("## ").append(role).append("\n\n").append(content).append("\n\n");
        }
    }
}

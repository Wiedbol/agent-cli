package com.agent.session;

import com.agent.llm.Message;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 会话存储：把一个会话写成 JSONL（一行一个事件），支持续写、读回与分片轮转。
 *
 * <p>事件有三种（每行都带 {@code type} 与 {@code at}）：
 *
 * <pre>
 * {"type":"meta",    "at":"...", "sessionId":"demo", "resumed":false, "part":1}
 * {"type":"turn",    "at":"...", "step":1, "elapsedMillis":820, "promptTokens":673, "completionTokens":53, "tools":"read_file"}
 * {"type":"message", "at":"...", "role":"tool", "content":"...", "toolCallId":"call_1", "redacted":true}
 * </pre>
 *
 * <p>三条来自需求的关键约束：
 *
 * <ul>
 *   <li><b>每条消息立即追加写</b>（F①）：不缓存、不批量，异常中断最多丢当前这一条；</li>
 *   <li><b>超过 5 MB 轮转</b>（R①）：{@code name.jsonl} → {@code name.part2.jsonl} → …，
 *       读回时按序号升序合并；</li>
 *   <li><b>写盘前过滤敏感内容</b>（K②）：见 {@link Redactor}，只替换落盘文本，内存历史不变。</li>
 * </ul>
 *
 * <p>记录失败（磁盘满、权限不足）不会抛到对话流程：只打印一条提示，
 * 因为「让对话继续」比「记录完整」优先级更高。
 */
public class SessionStore implements SessionRecorder {

    /** 打开会话的方式。 */
    public enum Mode {
        /** 有历史会话就续上，没有就新建（D③ 的默认行为）。 */
        AUTO,
        /** 强制新建（`--new`）。 */
        NEW,
        /** 必须续上已有会话，找不到就报错（`--resume`）。 */
        RESUME,
        /** 同名会话存在就续上，否则新建（`--session <名字>`）。 */
        NAMED
    }

    /** 读回结果：消息列表 + 被跳过的损坏行数。 */
    public record LoadResult(List<Message> messages, int skippedLines) {
    }

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path dir;
    private String sessionId;
    private final long maxBytes;
    private final PrintStream warn;
    private boolean resumed;
    private int part;
    private Path currentFile;

    private SessionStore(Path dir, String sessionId, boolean resumed, int part, long maxBytes, PrintStream warn) {
        this.dir = dir;
        this.sessionId = sessionId;
        this.resumed = resumed;
        this.part = part;
        this.maxBytes = maxBytes;
        this.warn = warn;
        this.currentFile = SessionPaths.shard(dir, sessionId, part);
    }

    /**
     * 打开一个会话。
     *
     * @param mode 打开方式
     * @param name 会话名；{@code null} 表示用时间戳。{@link Mode#RESUME} 时可传 {@code latest}
     * @throws IOException 目录不可写，或 RESUME 指定的会话不存在
     */
    public static SessionStore start(Path dir, Mode mode, String name) throws IOException {
        return start(dir, mode, name, SessionPaths.MAX_BYTES, null);
    }

    /** 供测试注入更小的轮转阈值与告警流。 */
    public static SessionStore start(Path dir, Mode mode, String name, long maxBytes, PrintStream warn)
            throws IOException {
        Files.createDirectories(dir);
        return switch (mode) {
            case RESUME -> resumeExisting(dir, name, maxBytes, warn);
            case NAMED -> exists(dir, name)
                    ? resumeExisting(dir, name, maxBytes, warn)
                    : newSession(dir, name, maxBytes, warn);
            case NEW -> newSession(dir, name, maxBytes, warn);
            case AUTO -> {
                Optional<String> latestId = latest(dir);
                yield latestId.isPresent()
                        ? resumeExisting(dir, latestId.get(), maxBytes, warn)
                        : newSession(dir, null, maxBytes, warn);
            }
        };
    }

    private static SessionStore newSession(Path dir, String name, long maxBytes, PrintStream warn)
            throws IOException {
        String id = name == null || name.isBlank() ? SessionPaths.timestampName() : uniqueName(dir, name.trim());
        SessionStore store = new SessionStore(dir, id, false, 1, maxBytes, warn);
        store.writeMeta(false);
        return store;
    }

    private static SessionStore resumeExisting(Path dir, String name, long maxBytes, PrintStream warn)
            throws IOException {
        if (name == null || name.isBlank()) {
            throw new IOException("恢复会话时必须给出会话名（或 latest）");
        }
        String id = "latest".equalsIgnoreCase(name)
                ? latest(dir).orElseThrow(() -> new IOException("没有任何可恢复的会话"))
                : name;
        if (!exists(dir, id)) {
            throw new IOException("会话不存在: " + id);
        }
        SessionStore store = new SessionStore(dir, id, true, lastPart(dir, id), maxBytes, warn);
        store.writeMeta(true);
        return store;
    }

    // ---- 写入（SessionRecorder 实现）----

    @Override
    public void appendMessage(Message message) {
        appendMessageNode(message, Redactor.redact(message.content()), null);
    }

    @Override
    public void appendToolResult(Message toolMessage, String toolArgumentsJson) {
        appendMessageNode(toolMessage,
                Redactor.redact(toolMessage.content(), toolArgumentsJson),
                toolMessage.toolCallId());
    }

    @Override
    public void appendTurn(int step, long elapsedMillis, int promptTokens, int completionTokens,
                           String toolNames) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "turn");
        node.put("at", now());
        node.put("step", step);
        node.put("elapsedMillis", elapsedMillis);
        node.put("promptTokens", promptTokens);
        node.put("completionTokens", completionTokens);
        node.put("tools", toolNames == null || toolNames.isBlank() ? "-" : toolNames);
        write(node);
    }

    private void appendMessageNode(Message message, Redactor.Result content, String toolCallId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "message");
        node.put("at", now());
        node.put("role", message.role());
        node.put("content", content.text());
        if (toolCallId != null) {
            node.put("toolCallId", toolCallId);
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            node.set("toolCalls", MAPPER.valueToTree(message.toolCalls()));
        }
        if (content.redacted()) {
            node.put("redacted", true);
        }
        write(node);
    }

    private void writeMeta(boolean resumedNow) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "meta");
        node.put("at", now());
        node.put("sessionId", sessionId);
        node.put("part", part);
        node.put("resumed", resumedNow);
        write(node);
    }

    /** 追加一行；必要时先轮转分片。写失败只提示，不打断对话。 */
    private void write(ObjectNode node) {
        String line = node.toString() + System.lineSeparator();
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try {
            if (Files.exists(currentFile) && Files.size(currentFile) + bytes.length > maxBytes) {
                rotate();
            }
            Files.write(currentFile, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException failed) {
            if (warn != null) {
                warn.println("[会话记录] 写入失败（对话继续）：" + failed.getMessage());
            }
        }
    }

    private void rotate() throws IOException {
        part++;
        currentFile = SessionPaths.shard(dir, sessionId, part);
        writeMeta(true);
    }

    /**
     * 切换到新会话文件（`clear` 的语义：只清内存并另开一个会话，旧记录一条都不删）。
     *
     * <p>方法内部改的是「当前分片指针」，因此记录器引用不需要替换 —— 已经在记录的角色会继续
     * 往新会话里写。
     *
     * @return 新会话名
     */
    public String startNewSession() throws IOException {
        this.sessionId = uniqueName(dir, SessionPaths.timestampName());
        this.part = 1;
        this.resumed = false;
        this.currentFile = SessionPaths.shard(dir, sessionId, 1);
        writeMeta(false);
        return sessionId;
    }

    // ---- 读回 ----

    /** 读回本会话的历史消息（meta / turn 事件会被忽略）。 */
    public LoadResult loadMessages() throws IOException {
        return load(dir, sessionId);
    }

    /**
     * 读回指定会话的消息。
     *
     * <p>损坏行（半截 JSON、被外部编辑破坏）会被跳过并计数，而不是让整个恢复失败。
     */
    public static LoadResult load(Path dir, String id) throws IOException {
        List<Message> messages = new ArrayList<>();
        int skipped = 0;
        for (Path shard : shards(dir, id)) {
            for (String line : Files.readAllLines(shard, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = MAPPER.readTree(line);
                    if (!"message".equals(node.path("type").asText())) {
                        continue;
                    }
                    ObjectNode messageNode = node.deepCopy();
                    messageNode.remove(List.of("type", "at", "redacted"));
                    messages.add(MAPPER.treeToValue(messageNode, Message.class));
                } catch (Exception corrupt) {
                    skipped++;
                }
            }
        }
        return new LoadResult(List.copyOf(messages), skipped);
    }

    /** 目录下所有会话名，最近的在前。 */
    public static List<String> listSessions(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        record Entry(String id, long modified) {
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String id = SessionPaths.sessionIdOf(file.getFileName().toString());
                if (id != null) {
                    entries.add(new Entry(id, Files.getLastModifiedTime(file).toMillis()));
                }
            }
        }
        entries.sort(Comparator.comparingLong(Entry::modified).reversed());
        Set<String> ids = new LinkedHashSet<>();
        for (Entry entry : entries) {
            ids.add(entry.id());
        }
        return List.copyOf(ids);
    }

    /** 最近一次会话的名字。 */
    public static Optional<String> latest(Path dir) throws IOException {
        List<String> sessions = listSessions(dir);
        return sessions.isEmpty() ? Optional.empty() : Optional.of(sessions.get(0));
    }

    /** 会话是否存在（至少有一个分片）。 */
    public static boolean exists(Path dir, String id) {
        return id != null && !id.isBlank() && Files.exists(SessionPaths.shard(dir, id, 1));
    }

    /** 某个会话的全部分片，按 part 序号升序。 */
    static List<Path> shards(Path dir, String id) throws IOException {
        if (!Files.isDirectory(dir) || id == null) {
            return List.of();
        }
        List<Path> shards = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (id.equals(SessionPaths.sessionIdOf(name))) {
                    shards.add(file);
                }
            }
        }
        shards.sort(Comparator.comparingInt(file -> partOf(file.getFileName().toString())));
        return shards;
    }

    private static int partOf(String fileName) {
        String base = fileName.substring(0, fileName.length() - ".jsonl".length());
        int index = base.lastIndexOf(".part");
        if (index < 0) {
            return 1;
        }
        try {
            return Integer.parseInt(base.substring(index + ".part".length()));
        } catch (NumberFormatException broken) {
            return Integer.MAX_VALUE;
        }
    }

    private static int lastPart(Path dir, String id) throws IOException {
        List<Path> shards = shards(dir, id);
        return shards.isEmpty() ? 1 : partOf(shards.get(shards.size() - 1).getFileName().toString());
    }

    private static String uniqueName(Path dir, String wanted) throws IOException {
        Set<String> taken = new LinkedHashSet<>(listSessions(dir));
        if (!taken.contains(wanted)) {
            return wanted;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = wanted + "-" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        throw new IOException("同名会话过多: " + wanted);
    }

    private static String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }

    // ---- 访问器 ----

    public String sessionId() {
        return sessionId;
    }

    public Path dir() {
        return dir;
    }

    public Path currentFile() {
        return currentFile;
    }

    /** 本次打开是「续上已有会话」还是「新建」。 */
    public boolean resumed() {
        return resumed;
    }

    /** 给用户看的会话描述。 */
    public String describe() {
        return (resumed ? "续用会话 " : "新建会话 ") + sessionId
                + "（" + dir.relativize(currentFile).toString().replace('\\', '/') + "）";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SessionStore store)) {
            return false;
        }
        return sessionId.equals(store.sessionId) && dir.equals(store.dir);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dir, sessionId);
    }

    @Override
    public String toString() {
        return "SessionStore{" + sessionId + " @ " + dir + "}";
    }
}

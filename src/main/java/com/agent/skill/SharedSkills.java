package com.agent.skill;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** 用户级索引仅保存引用，发布证据和指南仍由原技能库核验。 */
final class SharedSkills {
    private final Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    private final Path directory = home.resolve(".agent-cli/shared-skills");

    record Origin(String project, String control, String source) {
        static Origin of(Path project, Path control, Path source) {
            return new Origin(project.toString(), control == null ? null : control.toString(),
                    source == null ? null : source.toString());
        }

        void validate() throws IOException {
            if ((control == null) != (source == null)) { throw new IOException("共享技能的归档位置不完整"); }
            validatePath(project);
            if (control != null) { validatePath(control); validatePath(source); }
        }

        SkillStore open(PrintStream out) throws IOException {
            validate();
            Path owner = SkillTrials.canonical(Path.of(project));
            return control == null ? new SkillStore(owner)
                    : new SkillTrials(owner, out).library(Path.of(control), Path.of(source));
        }

        private static void validatePath(String path) throws IOException {
            try {
                if (path != null) {
                    Path value = Path.of(path);
                    if (value.isAbsolute() && value.normalize().equals(value)) { return; }
                }
            } catch (InvalidPathException invalid) {
                throw new IOException("共享技能来源路径无效", invalid);
            }
            throw new IOException("共享技能来源路径无效");
        }
    }

    record Entry(String versionId, String versionSha256, Origin origin,
                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
                 boolean learned) { }
    record Available(Entry entry, SkillStore store, SkillStore.Snapshot snapshot) { }

    void share(String id, Origin origin, PrintStream out) throws IOException {
        var snapshot = origin.open(out).snapshot(id);
        share(new Entry(id, SkillStore.digest(snapshot.version()), origin, false));
    }

    void shareLearned(ChatLearning.Entry learned, Path root) throws IOException {
        share(new Entry(learned.id(), SkillStore.digest(learned), Origin.of(root, null, null), true));
    }

    private void share(Entry entry) throws IOException {
        String id = entry.versionId();
        Path target = path(id);
        Files.createDirectories(directory);
        checkPath(target);
        Path temporary = Files.createTempFile(directory, "share-", ".tmp");
        try {
            Files.write(temporary, SkillStore.JSON.writeValueAsBytes(entry));
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException existing) {
                if (!entry.equals(read(id))) { throw new IOException("该版本已绑定到另一共享来源", existing); }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    boolean unshare(String id, Path project) throws IOException {
        Path target = path(id);
        if (Files.notExists(target, NOFOLLOW_LINKS)) { return false; }
        if (!project.toString().equals(read(id).origin().project())) {
            throw new IOException("请在共享技能的原项目执行此操作");
        }
        return Files.deleteIfExists(target);
    }

    Available resolve(String id, PrintStream out) throws IOException {
        Entry entry = read(id);
        return resolve(entry, out);
    }

    Available resolve(String id, Origin origin, String digest, PrintStream out) throws IOException {
        Entry entry = read(id);
        if (!entry.origin().equals(origin) || !entry.versionSha256().equals(digest)) {
            throw new IOException("共享技能的来源或版本摘要已改变: " + id);
        }
        return resolve(entry, out);
    }

    private Available resolve(Entry entry, PrintStream out) throws IOException {
        if (entry.learned()) { throw new IOException("该技能来自普通聊天，请用 /use-skill 引用"); }
        var store = entry.origin().open(out);
        var snapshot = store.snapshot(entry.versionId());
        if (!entry.versionSha256().equals(SkillStore.digest(snapshot.version()))) {
            throw new IOException("共享技能的版本摘要已改变: " + entry.versionId());
        }
        return new Available(entry, store, snapshot);
    }

    List<Available> available(PrintStream out) throws IOException {
        return available(out, "");
    }

    List<Available> available(PrintStream out, String prefix) throws IOException {
        var values = new ArrayList<Available>();
        for (Entry entry : entries(prefix)) {
            if (entry.learned()) { continue; }
            try { values.add(resolve(entry, out)); }
            catch (IOException unavailable) { /* 单条来源失效不影响其他技能。 */ }
        }
        return List.copyOf(values);
    }

    List<ChatLearning.Entry> learned(String prefix) throws IOException {
        var values = new ArrayList<ChatLearning.Entry>();
        for (Entry entry : entries(prefix)) {
            if (!entry.learned()) { continue; }
            try {
                var learned = new ChatLearning(Path.of(entry.origin().project())).read(entry.versionId());
                if (entry.versionSha256().equals(SkillStore.digest(learned))) { values.add(learned); }
            } catch (IOException unavailable) { /* 只显示可回读且摘要一致的普通聊天技能。 */ }
        }
        return List.copyOf(values);
    }

    private List<Entry> entries(String prefix) throws IOException {
        checkPath(directory);
        if (Files.notExists(directory, NOFOLLOW_LINKS)) { return List.of(); }
        if (ChatKnowledge.isFullId(prefix)) {
            Path target = path(prefix);
            return Files.exists(target, NOFOLLOW_LINKS) ? List.of(read(prefix)) : List.of();
        }
        try (var files = Files.list(directory)) {
            var entries = new ArrayList<Entry>();
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json") || !name.startsWith(prefix)) { continue; }
                try { entries.add(read(name.substring(0, name.length() - 5))); }
                catch (IOException unavailable) { /* 单条索引无效不影响其他记录。 */ }
            }
            return List.copyOf(entries);
        }
    }

    private Entry read(String id) throws IOException {
        Path entry = path(id);
        if (!Files.isRegularFile(entry, NOFOLLOW_LINKS) || Files.size(entry) > 32_768) {
            throw new IOException("共享技能不存在、已撤销或索引不可用: " + id);
        }
        var value = SkillStore.JSON.readValue(entry.toFile(), Entry.class);
        if (value == null || !id.equals(value.versionId()) || value.origin() == null
                || value.versionSha256() == null || !value.versionSha256().matches("[0-9a-f]{64}")) {
            throw new IOException("共享技能索引无效: " + id);
        }
        value.origin().validate();
        if (value.learned() && value.origin().control() != null) { throw new IOException("普通聊天技能必须引用来源项目"); }
        return value;
    }

    private Path path(String id) throws IOException {
        SkillStore.requireId(id);
        Path path = directory.resolve(id + ".json");
        checkPath(path);
        return path;
    }

    private void checkPath(Path path) throws IOException {
        Path realHome = home.toRealPath();
        for (Path part = path; !part.equals(home); part = part.getParent()) {
            if (Files.exists(part, NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(part) || !part.toRealPath().startsWith(realHome))) {
                throw new IOException("共享技能索引路径无效");
            }
        }
    }
}

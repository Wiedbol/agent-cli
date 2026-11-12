package com.agent.skill;

import com.agent.experience.ExperienceLibrary;
import com.agent.task.WorkspaceSnapshot;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 普通聊天可显式读取已核验知识，作为本轮开发需求的参考。 */
public final class ChatKnowledge {
    private static final Pattern FULL_ID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    private ChatKnowledge() { }

    public record Reference(String id, String advice, boolean learned) {
        Reference(String id, String advice) { this(id, advice, false); }
    }

    public static Reference experience(Path root, PrintStream out, String reference) throws IOException {
        var library = new ExperienceLibrary(root);
        String key = normalize(reference);
        var learned = new ChatLearning(root).local(key);
        ExperienceLibrary.Visible visible;
        if (isFullId(key)) {
            if (!learned.isEmpty()) { return learnedExperience(root, learned.get(0)); }
            visible = library.readVisible(key);
        } else {
            var available = library.visible(out);
            String id = resolvePrefix(key, Stream.concat(available.stream().map(entry -> entry.experience().id()),
                    learned.stream().map(ChatLearning.Entry::id)));
            for (var entry : learned) { if (id.equals(entry.id())) { return learnedExperience(root, entry); } }
            visible = available.stream().filter(entry -> entry.experience().id().equals(id)).findFirst().orElseThrow();
        }
        var experience = visible.experience();
        String id = experience.id();
        if (!experience.enabled() || !visible.shared() && !library.sourceValid(experience)) {
            throw new IOException("经验已停用或来源不可用: " + id);
        }
        var advice = experience.advice();
        requireConditions(root, advice.conditions());
        return new Reference(id, "失败经验 " + id + "\n" + ChatLearning.formatAdvice(advice));
    }

    private static Reference learnedExperience(Path root, ChatLearning.Entry entry) throws IOException {
        requireConditions(root, entry.experience().conditions());
        return new Reference(entry.id(), "普通聊天经验（用户确认）\n" + ChatLearning.formatAdvice(entry.experience()), true);
    }

    private static Reference learnedSkill(Path root, ChatLearning.Entry entry) throws IOException {
        requireConditions(root, entry.skill().conditions());
        return new Reference(entry.id(), "普通聊天技能（用户确认）\n" + entry.skill().markdown(), true);
    }

    public static Reference skill(Path root, Path control, Path source, PrintStream out, String reference) throws IOException {
        if ((control == null) != (source == null)) { throw new IOException("技能库控制与来源必须成对指定"); }
        var store = control == null ? new SkillStore(root) : new SkillTrials(root, out).library(control, source);
        var shared = new SharedSkills();
        String key = normalize(reference);
        SkillStore.Snapshot snapshot;
        if (isFullId(key)) {
            if (store.exists("versions", key)) { snapshot = store.snapshot(key); }
            else {
                var learned = new ChatLearning(root).skills(key);
                if (!learned.isEmpty()) { return learnedSkill(root, learned.get(0)); }
                snapshot = shared.resolve(key, out).snapshot();
            }
        } else {
            var local = store.ids("versions");
            var available = shared.available(out, key);
            var learned = new ChatLearning(root).skills(key);
            String id = resolvePrefix(key, Stream.concat(Stream.concat(local.stream(),
                    available.stream().map(entry -> entry.entry().versionId())), learned.stream().map(ChatLearning.Entry::id)));
            for (var entry : learned) { if (id.equals(entry.id())) { return learnedSkill(root, entry); } }
            snapshot = local.contains(id) ? store.snapshot(id) : available.stream()
                    .filter(entry -> entry.entry().versionId().equals(id)).findFirst().orElseThrow().snapshot();
        }
        String id = snapshot.version().id();
        var guide = snapshot.candidate().guide();
        requireConditions(root, guide.conditions());
        return new Reference(id, "已发布技能版本 " + id + "\n" + guide.markdown());
    }

    static String normalize(String reference) throws IOException {
        String key = reference.toLowerCase(Locale.ROOT);
        if (!isFullId(key) && !key.matches("[0-9a-f][0-9a-f-]{0,34}")) {
            throw new IOException("请输入完整 ID 或其开头的若干位");
        }
        return key;
    }

    static boolean isFullId(String key) {
        return FULL_ID.matcher(key).matches();
    }

    static String resolvePrefix(String prefix, Stream<String> ids) throws IOException {
        var matches = ids.filter(id -> id.startsWith(prefix)).distinct().sorted().limit(2).toList();
        if (matches.isEmpty()) { throw new IOException("找不到匹配的 ID 前缀: " + prefix); }
        if (matches.size() > 1) {
            throw new IOException("ID 前缀匹配多个条目，请输入更多位: " + String.join(", ", matches));
        }
        return matches.get(0);
    }

    private static void requireConditions(Path root, String conditions) throws IOException {
        if (SkillMatching.hasFilePreconditions(conditions)) {
            String failure = SkillMatching.conditionFailure(conditions, WorkspaceSnapshot.capture(root.toRealPath()));
            if (failure != null) { throw new IOException(failure); }
        }
    }
}

package com.agent.skill;

import com.agent.experience.Experience;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatKnowledgeTest {
    @Test
    void shouldRequireLongerPrefixWhenTwoIdsMatch() throws Exception {
        String first = "d2c8347b-57f9-4fcb-bacb-68da11d50d7e";
        String second = "d2c8347b-608a-4fcb-bacb-68da11d50d7e";
        var ambiguous = assertThrows(IOException.class,
                () -> ChatKnowledge.resolvePrefix("d2c8347b", Stream.of(first, second)));
        assertTrue(ambiguous.getMessage().contains("请输入更多位"));
        assertEquals(first, ChatKnowledge.resolvePrefix("d2c8347b-5", Stream.of(first, first, second)));
        assertThrows(IOException.class, () -> ChatKnowledge.resolvePrefix("abcd", Stream.of(first, second)));
    }

    @Test
    void shouldKeepLearnedSharedSkillsOutOfPublishedCatalogAndVerifyOriginalDigest(@TempDir Path root) throws Exception {
        Path home = Files.createDirectory(root.resolve("home"));
        Path owner = Files.createDirectory(root.resolve("owner")).toRealPath();
        var store = new SkillStore(owner);
        String id = "d2c8347b-57f9-4fcb-bacb-68da11d50d7e";
        var entry = new ChatLearning.Entry(1, id, store.project(), "chat", 1, "回显正确",
                new Experience.Advice("终端回显", "乱码", "核对编码", "文件读取"),
                new SkillCandidate.Guide("编码", "终端回显", "文件读取", "检查字节", "按编码读写", "检查中文"));
        store.writeNew("chat-learning", id, entry);

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var shared = new SharedSkills();
            shared.shareLearned(entry, owner);
            assertEquals(java.util.List.of(entry), shared.learned(id));
            assertTrue(shared.available(System.out).isEmpty());
            assertThrows(IOException.class, () -> shared.resolve(id, System.out));

            var changed = new ChatLearning.Entry(1, id, store.project(), "chat", 1, "验证结果已改变",
                    entry.experience(), entry.skill());
            store.write("chat-learning", id, changed);
            assertTrue(shared.learned(id).isEmpty());
        } finally { System.setProperty("user.home", previousHome); }
    }
}

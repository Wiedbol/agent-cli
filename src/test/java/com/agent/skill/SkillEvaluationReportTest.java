package com.agent.skill;

import com.agent.evaluation.EvaluationRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillEvaluationReportTest {
    @TempDir Path root;

    private SkillEvaluation.Report saved(int schemaVersion) throws Exception {
        Path archive = Files.createDirectory(root.resolve("report-" + schemaVersion));
        var config = new SkillEvaluation.Config(root, root, root.resolve("catalog.json"), root.resolve("inputs.json"),
                archive, "v1", "v2", "offline-model", "offline", 10, 10, 1, true);
        var report = new SkillEvaluation.Report(); report.schemaVersion = schemaVersion; report.kind = "SIMULATED";
        report.frozen = new SkillEvaluation.Freeze(config, root.toString(), SkillStore.JSON.createObjectNode(), Map.of(),
                SkillStore.JSON.createObjectNode(),
                Map.of("example", new EvaluationRunner.Scenario("边界", Map.of("b.java", "b", "a.java", "a"))),
                List.of(), Map.of("b", "2", "a", "1"), List.of(), Map.of("sampling", "default", "timeout", 60));
        report.freezeSha256 = digest(report.frozen, schemaVersion);
        Method save = SkillEvaluation.class.getDeclaredMethod("save", SkillEvaluation.Report.class);
        save.setAccessible(true); save.invoke(null, report);
        return report;
    }

    private String digest(Object value, int schemaVersion) throws Exception {
        Method digest = SkillEvaluation.class.getDeclaredMethod("reportDigest", Object.class, int.class);
        digest.setAccessible(true);
        return (String) digest.invoke(null, value, schemaVersion);
    }

    private JsonNode reversed(JsonNode value) {
        if (value.isObject()) {
            var result = SkillStore.JSON.createObjectNode();
            var names = new ArrayList<String>(); value.fieldNames().forEachRemaining(names::add);
            Collections.reverse(names);
            for (String name : names) { result.set(name, reversed(value.get(name))); }
            return result;
        }
        if (value.isArray()) {
            var result = SkillStore.JSON.createArrayNode();
            value.forEach(item -> result.add(reversed(item)));
            return result;
        }
        return value;
    }

    @Test void shouldReadNewReportAfterObjectKeyOrderChanges() throws Exception {
        var report = saved(2);
        Path archive = report.frozen.config().archive(), file = archive.resolve("report.json");
        JsonNode wrapper = SkillStore.JSON.readTree(file.toFile());
        SkillStore.JSON.writeValue(file.toFile(), reversed(wrapper));

        var read = SkillEvaluation.read(archive);
        assertEquals(report.freezeSha256, read.freezeSha256);
        assertEquals(report.frozen, read.frozen);
    }

    @Test void shouldKeepLegacyReportsReadableAndRejectLegacyDigestMismatch() throws Exception {
        var report = saved(1);
        Path archive = report.frozen.config().archive(), file = archive.resolve("report.json");
        assertEquals(report.freezeSha256, SkillEvaluation.read(archive).freezeSha256);
        var wrapper = (ObjectNode) SkillStore.JSON.readTree(file.toFile());
        ((ObjectNode) wrapper.path("value")).put("freezeSha256", "0".repeat(64));
        wrapper.put("sha256", SkillStore.digest(wrapper.path("value")));
        SkillStore.JSON.writeValue(file.toFile(), wrapper);
        assertThrows(IOException.class, () -> SkillEvaluation.read(archive));
    }

    @Test void shouldRejectChangedContentEvenWhenOuterDigestIsRecomputed() throws Exception {
        var report = saved(2);
        Path archive = report.frozen.config().archive(), file = archive.resolve("report.json");
        var wrapper = (ObjectNode) SkillStore.JSON.readTree(file.toFile());
        ((ObjectNode) wrapper.path("value").path("frozen").path("configuration")).put("timeout", 600);
        SkillStore.JSON.writeValue(file.toFile(), wrapper);
        assertThrows(IOException.class, () -> SkillEvaluation.read(archive));

        wrapper.put("sha256", digest(wrapper.path("value"), 2));
        SkillStore.JSON.writeValue(file.toFile(), wrapper);
        assertThrows(IOException.class, () -> SkillEvaluation.read(archive));
    }

    @Test void shouldKeepArrayOrderInNewDigestAndRejectUnknownSchema() throws Exception {
        assertNotEquals(digest(List.of("v1", "v2"), 2), digest(List.of("v2", "v1"), 2));
        var report = saved(2);
        Path archive = report.frozen.config().archive(), file = archive.resolve("report.json");
        var wrapper = (ObjectNode) SkillStore.JSON.readTree(file.toFile());
        ((ObjectNode) wrapper.path("value")).put("schemaVersion", 99);
        SkillStore.JSON.writeValue(file.toFile(), wrapper);
        assertThrows(IOException.class, () -> SkillEvaluation.read(archive));
    }
}

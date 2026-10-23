import com.agent.experience.Experience;
import com.agent.experience.ExperienceLibrary;
import com.agent.task.TaskRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 离线重放选择规则，只诊断归档证据；不代表保存了实际网络请求。 */
public class ReplaySelection {
    public static void main(String[] args) throws Exception {
        Path archive = Path.of(args[0]);
        var json = new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        List<Experience> frozen = json.readValue(archive.resolve("frozen.json").toFile(), new TypeReference<>() { });
        var rows = new ArrayList<Map<String, Object>>();
        for (var run : json.readTree(archive.resolve("results.json").toFile())) {
            int order = run.path("run").path("order").asInt();
            Path directory = archive.resolve("run-" + order + "/.agent/tasks");
            try (var paths = Files.walk(directory)) {
                var state = paths.filter(path -> path.getFileName().toString().equals("state.json")).findFirst().orElseThrow();
                TaskRecord task = json.readValue(state.toFile(), TaskRecord.class);
                var initialPaths = new java.util.HashSet<String>();
                run.path("initial").fieldNames().forEachRemaining(initialPaths::add);
                boolean stablePaths = initialPaths.equals(task.files.keySet())
                        && task.progress.decisions.stream().flatMap(decision -> decision.operations.stream())
                        .flatMap(operation -> operation.changes.values().stream())
                        .allMatch(change -> change.before() != null && change.after() != null);
                boolean redacted = task.checkpoint.messages.stream().anyMatch(message -> message.content() != null
                        && message.content().contains(com.agent.session.Redactor.PLACEHOLDER));
                String user = "";
                int replayed = 0;
                var selected = new ArrayList<String>();
                for (var message : task.checkpoint.messages) {
                    if ("user".equals(message.role())) { user = message.content(); }
                    if ("assistant".equals(message.role())) {
                        replayed++;
                        if (task.experiencesEnabled) {
                            var current = new ArrayList<String>();
                            ExperienceLibrary.adviceFromValidated(frozen, user, task.files, current,
                                    new PrintStream(OutputStream.nullOutputStream()));
                            current.forEach(id -> { if (!selected.contains(id)) { selected.add(id); } });
                        }
                    }
                }
                rows.add(Map.of("order", order, "enabled", task.experiencesEnabled,
                        "requests", task.progress.requests, "replayedResponses", replayed,
                        "selectedIds", selected, "stablePaths", stablePaths, "redactedMessages", redacted,
                        "basis", "offline replay using persisted user messages and final file paths; not wire capture"));
            }
        }
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), rows);
    }
}

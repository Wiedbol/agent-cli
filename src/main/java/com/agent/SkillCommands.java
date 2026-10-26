package com.agent;

import com.agent.llm.ApiKeyLoader;
import com.agent.llm.DeepSeekClient;
import com.agent.llm.LLMClient;
import com.agent.skill.SkillTrials;

import java.nio.file.Path;
import java.util.*;

/** 新技能命令独立解析，避免把不认识的预算参数落回普通聊天或旧任务入口。 */
final class SkillCommands {
    private static final Set<String> FLAGS = Set.of("--start-skill-batch", "--request-limit", "--generate-skill",
            "--skill-batch", "--revise-skill", "--revision-reason", "--skill-candidates", "--skill-candidate", "--skill-batches",
            "--trial-skill", "--trial-control", "--trial-source", "--trial-catalog", "--trial-max-steps", "--skill-trial", "--skill-trials",
            "--skill-review", "--approve-skill", "--reject-skill", "--attest-skill-trial", "--skill-version", "--skill-versions",
            "--skill-digest", "--related-trial", "--counterexample-trial", "--review-note", "--disable-skill", "--enable-skill");
    private static final Set<String> SWITCHES = Set.of("--start-skill-batch", "--skill-candidates", "--skill-batches", "--skill-trials", "--skill-versions");
    enum Action { START, GENERATE, CANDIDATES, CANDIDATE, BATCHES, BATCH, TRIAL, TRIAL_SHOW, TRIALS, REVIEW, APPROVE, REJECT, ATTEST, VERSION, VERSIONS, DISABLE, ENABLE }
    record Options(Action action, Path root, int limit, List<String> sources, String batch, String parent, String reason,
                   String candidate, SkillTrials.Config trial, Path trialControl, String trialId, Path librarySource,
                   com.agent.skill.SkillReviews.Selection review, String note, String digest, String versionId) { }

    static Options parse(String[] args) {
        if (args == null || Arrays.stream(args).noneMatch(FLAGS::contains)) { return null; }
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (!FLAGS.contains(flag) && !flag.equals("--root")) { throw new IllegalArgumentException("技能操作不能混用参数: " + flag); }
            if (options.containsKey(flag)) { throw new IllegalArgumentException("技能参数不能重复: " + flag); }
            String value = "true";
            if (!SWITCHES.contains(flag)) {
                if (i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) { throw new IllegalArgumentException(flag + " 需要非空参数"); }
                value = args[++i];
            }
            options.put(flag, value);
        }
        var commands = Map.ofEntries(Map.entry("--start-skill-batch", Action.START), Map.entry("--generate-skill", Action.GENERATE),
                Map.entry("--skill-candidates", Action.CANDIDATES), Map.entry("--skill-candidate", Action.CANDIDATE), Map.entry("--skill-batches", Action.BATCHES),
                Map.entry("--trial-skill", Action.TRIAL), Map.entry("--skill-trial", Action.TRIAL_SHOW), Map.entry("--skill-trials", Action.TRIALS),
                Map.entry("--skill-review", Action.REVIEW), Map.entry("--approve-skill", Action.APPROVE), Map.entry("--reject-skill", Action.REJECT),
                Map.entry("--attest-skill-trial", Action.ATTEST), Map.entry("--skill-version", Action.VERSION), Map.entry("--skill-versions", Action.VERSIONS),
                Map.entry("--disable-skill", Action.DISABLE), Map.entry("--enable-skill", Action.ENABLE));
        var modes = commands.keySet().stream().filter(options::containsKey).toList();
        if (modes.size() > 1 || modes.isEmpty() && !options.containsKey("--skill-batch")) { throw new IllegalArgumentException("每次只能选择一个技能操作"); }
        String primary = modes.isEmpty() ? "--skill-batch" : modes.get(0);
        Action action = commands.getOrDefault(primary, Action.BATCH);
        Set<String> required = switch (action) {
            case START -> Set.of("--request-limit");
            case GENERATE -> Set.of("--skill-batch");
            case TRIAL -> Set.of("--skill-batch", "--trial-control", "--trial-source", "--trial-catalog", "--trial-max-steps");
            case TRIAL_SHOW, TRIALS -> Set.of("--trial-control");
            case ATTEST -> Set.of("--trial-control", "--review-note");
            case APPROVE -> Set.of("--trial-control", "--trial-source", "--skill-digest", "--related-trial", "--counterexample-trial");
            case REVIEW -> Set.of("--trial-control", "--trial-source", "--skill-digest");
            case REJECT, DISABLE, ENABLE -> Set.of("--trial-control", "--trial-source", "--skill-digest", "--review-note");
            case VERSION, VERSIONS -> Set.of("--trial-control", "--trial-source");
            default -> Set.of();
        };
        var allowed = new HashSet<>(required); allowed.add(primary); allowed.add("--root");
        boolean archiveOptional = Set.of(Action.START, Action.GENERATE, Action.CANDIDATES, Action.CANDIDATE, Action.BATCHES, Action.BATCH).contains(action);
        if (archiveOptional) { allowed.addAll(Set.of("--trial-control", "--trial-source")); }
        if (action == Action.GENERATE) { allowed.addAll(Set.of("--revise-skill", "--revision-reason")); }
        if (action == Action.REVIEW) { allowed.addAll(Set.of("--related-trial", "--counterexample-trial")); }
        if (!options.keySet().containsAll(required) || !allowed.containsAll(options.keySet())) { throw new IllegalArgumentException("技能操作参数缺失或冲突: " + action); }
        if (archiveOptional && options.containsKey("--trial-control") != options.containsKey("--trial-source")
                || options.containsKey("--revise-skill") != options.containsKey("--revision-reason")
                || action == Action.REVIEW && options.containsKey("--related-trial") != options.containsKey("--counterexample-trial")) {
            throw new IllegalArgumentException("控制/来源、父版本/原因和正反例参数必须成对指定");
        }
        int limit = action == Action.START ? Integer.parseInt(options.get("--request-limit")) : 0;
        int requests = action == Action.TRIAL ? Integer.parseInt(options.get("--trial-max-steps")) : 0;
        if (action == Action.START && limit <= 0 || action == Action.TRIAL && requests <= 0) { throw new IllegalArgumentException("请求额度必须为正整数"); }
        Path control = options.containsKey("--trial-control") ? Path.of(options.get("--trial-control")) : null;
        Path source = options.containsKey("--trial-source") ? Path.of(options.get("--trial-source")) : null;
        var config = action == Action.TRIAL ? new SkillTrials.Config(control, source, Path.of(options.get("--trial-catalog")), options.get(primary),
                options.get("--skill-batch"), requests, DeepSeekClient.defaultModel(), DeepSeekClient.defaultEndpoint()) : null;
        var selection = action == Action.REVIEW || action == Action.APPROVE ? new com.agent.skill.SkillReviews.Selection(control, source,
                options.get(primary), options.get("--skill-digest"), options.get("--related-trial"), options.get("--counterexample-trial")) : null;
        List<String> sources = action == Action.GENERATE ? Arrays.stream(options.get(primary).split(",", -1)).map(String::trim).toList() : List.of();
        if (sources.stream().anyMatch(String::isBlank)) { throw new IllegalArgumentException("来源经验列表不能有空项"); }
        return new Options(action, Path.of(options.getOrDefault("--root", ".")).toAbsolutePath().normalize(), limit, sources,
                options.get("--skill-batch"), options.get("--revise-skill"), options.get("--revision-reason"),
                Set.of(Action.CANDIDATE, Action.REJECT).contains(action) ? options.get(primary) : null, config, control,
                action == Action.TRIAL_SHOW || action == Action.ATTEST ? options.get(primary) : null, source, selection,
                options.get("--review-note"), options.get("--skill-digest"), Set.of(Action.VERSION, Action.DISABLE, Action.ENABLE).contains(action) ? options.get(primary) : null);
    }
    static int run(Options options) {
        LLMClient model = null;
        if (options.action() == Action.GENERATE || options.action() == Action.TRIAL) {
            String key = ApiKeyLoader.loadDeepSeekKey();
            if (key == null || key.isBlank()) { System.err.println("生成或试用需要 DeepSeek Key；技能查询无需 Key"); return 2; }
            model = DeepSeekClient.singleAttempt(key, DeepSeekClient.defaultEndpoint(), DeepSeekClient.defaultModel());
        }
        var cli = new CodingAgentCli(options.root(), model, false, 1, ConsoleStreams.newStdinReader(), System.out, System.err);
        return switch (options.action()) {
            case START -> cli.startSkillBatch(options.trialControl(), options.librarySource(), options.limit());
            case GENERATE -> cli.generateSkillCandidate(options.trialControl(), options.librarySource(), options.batch(), options.sources(), options.parent(), options.reason());
            case CANDIDATES -> cli.listSkillCandidates(options.trialControl(), options.librarySource());
            case CANDIDATE -> cli.showSkillCandidate(options.trialControl(), options.librarySource(), options.candidate());
            case BATCHES -> cli.listSkillBatches(options.trialControl(), options.librarySource());
            case BATCH -> cli.showSkillBatch(options.trialControl(), options.librarySource(), options.batch());
            case TRIAL -> cli.startSkillTrial(options.trial());
            case TRIAL_SHOW -> cli.showSkillTrial(options.trialControl(), options.trialId());
            case TRIALS -> cli.listSkillTrials(options.trialControl());
            case REVIEW -> cli.reviewSkill(options.review());
            case APPROVE -> cli.approveSkill(options.review(), ConsoleStreams.hasInteractiveConsole());
            case REJECT -> cli.rejectSkill(options.trialControl(), options.librarySource(), options.candidate(), options.digest(), options.note(), ConsoleStreams.hasInteractiveConsole());
            case ATTEST -> cli.attestSkillTrial(options.trialControl(), options.trialId(), options.note(), ConsoleStreams.hasInteractiveConsole());
            case VERSION -> cli.showSkillVersion(options.trialControl(), options.librarySource(), options.versionId());
            case VERSIONS -> cli.listSkillVersions(options.trialControl(), options.librarySource());
            case DISABLE, ENABLE -> cli.setSkillEnabled(options.trialControl(), options.librarySource(), options.versionId(), options.digest(),
                    options.action() == Action.ENABLE, options.note(), ConsoleStreams.hasInteractiveConsole());
        };
    }
}

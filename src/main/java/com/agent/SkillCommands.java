package com.agent;

import com.agent.llm.ApiKeyLoader;
import com.agent.llm.DeepSeekClient;
import com.agent.llm.LLMClient;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okio.BufferedSink;
import com.agent.skill.SkillTrials;
import java.io.IOException;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 新技能命令独立解析，避免把不认识的预算参数落回普通聊天或旧任务入口。 */
final class SkillCommands {
    private static final Set<String> FLAGS = Set.of("--start-skill-batch", "--request-limit", "--generate-skill",
            "--skill-batch", "--revise-skill", "--revision-reason", "--skill-candidates", "--skill-candidate", "--skill-batches",
            "--trial-skill", "--trial-control", "--trial-source", "--trial-catalog", "--trial-max-steps", "--skill-trial", "--skill-trials");
    private static final Set<String> SWITCHES = Set.of("--start-skill-batch", "--skill-candidates", "--skill-batches", "--skill-trials");
    enum Action { START, GENERATE, CANDIDATES, CANDIDATE, BATCHES, BATCH, TRIAL, TRIAL_SHOW, TRIALS }
    record Options(Action action, Path root, int limit, List<String> sources, String batch, String parent, String reason,
                   String candidate, SkillTrials.Config trial, Path trialControl, String trialId) { }

    static Options parse(String[] args) {
        if (args == null || Arrays.stream(args).noneMatch(FLAGS::contains)) { return null; }
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (!FLAGS.contains(flag) && !flag.equals("--root")) { throw new IllegalArgumentException("技能操作不能混用参数: " + flag); }
            if (options.containsKey(flag)) { throw new IllegalArgumentException("技能参数不能重复: " + flag); }
            String value = "true";
            if (!SWITCHES.contains(flag)) {
                if (i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException(flag + " 需要非空参数");
                }
                value = args[++i];
            }
            options.put(flag, value);
        }
        int modes = 0;
        for (String flag : List.of("--start-skill-batch", "--generate-skill", "--skill-candidates", "--skill-candidate", "--skill-batches", "--trial-skill", "--skill-trial", "--skill-trials")) {
            if (options.containsKey(flag)) { modes++; }
        }
        if (modes == 0 && options.containsKey("--skill-batch")) { modes++; }
        if (modes != 1) { throw new IllegalArgumentException("每次只能选择一个技能操作"); }
        boolean start = options.containsKey("--start-skill-batch");
        boolean generate = options.containsKey("--generate-skill");
        boolean trial = options.containsKey("--trial-skill");
        boolean trialQuery = options.containsKey("--skill-trial") || options.containsKey("--skill-trials");
        if (start != options.containsKey("--request-limit")) { throw new IllegalArgumentException("仅新批次必须明确 --request-limit 正整数上限"); }
        int limit = start ? Integer.parseInt(options.get("--request-limit")) : 0;
        if (start && limit <= 0) { throw new IllegalArgumentException("批次请求上限必须为正数"); }
        if ((generate || trial) && !options.containsKey("--skill-batch") || !generate && !trial && modes == 1
                && options.containsKey("--skill-batch") && options.size() > (options.containsKey("--root") ? 2 : 1)) {
            throw new IllegalArgumentException("生成必须指定批次，批次查询不能混用其他操作");
        }
        if ((options.containsKey("--revise-skill") || options.containsKey("--revision-reason"))
                && (!generate || !options.containsKey("--revise-skill") || !options.containsKey("--revision-reason"))) {
            throw new IllegalArgumentException("修订必须同时指定父版本与原因，且只用于生成");
        }
        if (options.containsKey("--trial-control") != (trial || trialQuery)) { throw new IllegalArgumentException("试用或试用查询必须指定 --trial-control"); }
        for (String flag : List.of("--trial-source", "--trial-catalog", "--trial-max-steps")) {
            if (options.containsKey(flag) != trial) { throw new IllegalArgumentException("试用必须且只能在启动时指定 " + flag); }
        }
        int requests = trial ? Integer.parseInt(options.get("--trial-max-steps")) : 0;
        if (trial && requests <= 0) { throw new IllegalArgumentException("试用总任务预算必须为正数"); }
        Path control = trial || trialQuery ? Path.of(options.get("--trial-control")) : null;
        var config = trial ? new SkillTrials.Config(control, Path.of(options.get("--trial-source")), Path.of(options.get("--trial-catalog")),
                options.get("--trial-skill"), options.get("--skill-batch"), requests, DeepSeekClient.defaultModel(), DeepSeekClient.defaultEndpoint()) : null;
        Action action = trial ? Action.TRIAL : options.containsKey("--skill-trial") ? Action.TRIAL_SHOW : options.containsKey("--skill-trials")
                ? Action.TRIALS : start ? Action.START : generate ? Action.GENERATE : options.containsKey("--skill-candidates")
                ? Action.CANDIDATES : options.containsKey("--skill-candidate") ? Action.CANDIDATE
                : options.containsKey("--skill-batches") ? Action.BATCHES : Action.BATCH;
        List<String> sources = generate ? Arrays.stream(options.get("--generate-skill").split(",", -1)).map(String::trim).toList() : List.of();
        if (sources.stream().anyMatch(String::isBlank)) { throw new IllegalArgumentException("来源经验列表不能有空项"); }
        return new Options(action, Path.of(options.getOrDefault("--root", ".")).toAbsolutePath().normalize(), limit, sources,
                options.get("--skill-batch"), options.get("--revise-skill"), options.get("--revision-reason"), options.get("--skill-candidate"), config, control, options.get("--skill-trial"));
    }
    static int run(Options options) {
        LLMClient model = null;
        if (options.action() == Action.GENERATE || options.action() == Action.TRIAL) {
            String key = ApiKeyLoader.loadDeepSeekKey();
            if (key == null || key.isBlank()) { System.err.println("生成或试用需要 DeepSeek Key；技能查询无需 Key"); return 2; }
            // 每个 HTTP 请求体只发一次；同一试用客户端仍可执行多次已分别预留的请求。
            model = new DeepSeekClient(key, DeepSeekClient.defaultEndpoint(), DeepSeekClient.defaultModel(), new OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                    .addInterceptor(chain -> {
                        RequestBody body = Objects.requireNonNull(chain.request().body());
                        RequestBody once = new RequestBody() {
                            @Override public MediaType contentType() { return body.contentType(); }
                            @Override public long contentLength() throws IOException { return body.contentLength(); }
                            @Override public void writeTo(BufferedSink sink) throws IOException { body.writeTo(sink); }
                            @Override public boolean isOneShot() { return true; }
                        };
                        return chain.proceed(chain.request().newBuilder().post(once).build());
                    }).build());
        }
        var cli = new CodingAgentCli(options.root(), model, false, 1, ConsoleStreams.newStdinReader(), System.out, System.err);
        return switch (options.action()) {
            case START -> cli.startSkillBatch(options.limit());
            case GENERATE -> cli.generateSkillCandidate(options.batch(), options.sources(), options.parent(), options.reason());
            case CANDIDATES -> cli.listSkillCandidates();
            case CANDIDATE -> cli.showSkillCandidate(options.candidate());
            case BATCHES -> cli.listSkillBatches();
            case BATCH -> cli.showSkillBatch(options.batch());
            case TRIAL -> cli.startSkillTrial(options.trial());
            case TRIAL_SHOW -> cli.showSkillTrial(options.trialControl(), options.trialId());
            case TRIALS -> cli.listSkillTrials(options.trialControl());
        };
    }
}

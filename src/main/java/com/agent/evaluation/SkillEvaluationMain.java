package com.agent.evaluation;

import com.agent.CodingAgentCli;
import com.agent.ConsoleStreams;
import com.agent.llm.ApiKeyLoader;
import com.agent.llm.DeepSeekClient;
import com.agent.skill.SkillEvaluation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;

/** 真实调用必须显式选择；本机HTTP模拟与只读报告均不读取模型Key。 */
public final class SkillEvaluationMain {
    private SkillEvaluationMain() { }
    public static void main(String[] args) {
        ConsoleStreams.configureStdout(); ConsoleStreams.configureStderr();
        int code = run(args, System.out, System.err);
        if (code != 0) { System.exit(code); }
    }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 2 && args[0].equals("--report")) {
                SkillEvaluation.print(SkillEvaluation.read(Path.of(args[1]).toAbsolutePath().normalize()), out);
                return 0;
            }
            if (args.length != 3 || !args[0].equals("--real") && !args[0].equals("--simulate")) {
                throw new IllegalArgumentException("用法：--real|--simulate <规范工作根> <外部config.json>；--report <归档>");
            }
            var json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var config = json.readValue(Path.of(args[2]).toFile(), SkillEvaluation.Config.class);
            boolean simulated = args[0].equals("--simulate");
            if (config.simulated() != simulated) { throw new IllegalArgumentException("模式必须与冻结配置的simulated一致"); }
            String key;
            if (simulated) {
                URI endpoint = URI.create(config.endpoint());
                if (!"http".equals(endpoint.getScheme()) || !java.util.Set.of("127.0.0.1", "localhost").contains(endpoint.getHost())
                        || endpoint.getUserInfo() != null || endpoint.getPort() < 1) { throw new IllegalArgumentException("SIMULATED只允许有明确端口的本机HTTP替身"); }
                key = "offline-fixture";
            } else {
                key = ApiKeyLoader.loadDeepSeekKey();
                if (key == null || key.isBlank()) { throw new IllegalArgumentException("REAL需要Key及另行批准的冻结请求额度"); }
            }
            var cli = new CodingAgentCli(Path.of(args[1]), null, false, 1, ConsoleStreams.newStdinReader(), out, err);
            return cli.startSkillEvaluation(config, run -> DeepSeekClient.singleAttempt(key, config.endpoint(), config.model()));
        } catch (IllegalArgumentException invalid) { err.println("参数错误: " + invalid.getMessage()); return 2; }
        catch (Exception failed) { err.println("技能对照失败: " + failed.getMessage()); return 4; }
    }
}

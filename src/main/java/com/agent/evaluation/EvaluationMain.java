package com.agent.evaluation;

import com.agent.ConsoleStreams;
import com.agent.command.ProcessCommandRunner;
import com.agent.llm.ApiKeyLoader;
import com.agent.llm.DeepSeekClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;

/** 真实矩阵必须显式选择；普通启动与离线回归不会调用此入口。 */
public final class EvaluationMain {
    public static void main(String[] args) throws Exception {
        ConsoleStreams.configureStdout();
        ConsoleStreams.configureStderr();
        if (args.length != 4 || !"--real".equals(args[0])) {
            throw new IllegalArgumentException("用法：--real <已有来源的工作根> <不存在的外部归档目录> <外部initials.json>");
        }
        String key = ApiKeyLoader.loadDeepSeekKey();
        if (key == null || key.isBlank()) { throw new IllegalArgumentException("需要模型Key；此入口会执行18次真实评测任务"); }
        Path root = Path.of(args[1]).toRealPath();
        if (Path.of(args[3]).toRealPath().startsWith(root)) {
            throw new IllegalArgumentException("初始模板集合必须位于模型工作根之外");
        }
        var scenarios = new ObjectMapper().readValue(Path.of(args[3]).toFile(),
                new TypeReference<Map<String, EvaluationRunner.Scenario>>() { });
        String model = DeepSeekClient.defaultModel();
        String endpoint = DeepSeekClient.defaultEndpoint();
        var config = new EvaluationRunner.Config(model, "mvn test", 12, false);
        var results = new EvaluationRunner().execute(Path.of(args[1]), Path.of(args[2]), scenarios, config,
                run -> new DeepSeekClient(key, endpoint, model), new ProcessCommandRunner());
        System.out.println("REAL：已记录" + results.size() + "次任务；失败和暂停均保留。来源/试跑不计入矩阵。");
    }
}

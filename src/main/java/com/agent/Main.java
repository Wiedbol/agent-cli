package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;

public class Main {

    private static final String QUIT_COMMAND = "quit";

    public static void main(String[] args) throws IOException {
        // 先按环境把 stdout 固定为「终端能正确解码的编码」，再取 System.out
        ConsoleStreams.configureStdout();
        run(new Agent(), ConsoleStreams.newStdinReader(), System.out);
    }

    /**
     * I/O 循环：逐行读取 → 交给 {@code Agent} → 打印；读到 {@code quit} 或 EOF 结束。
     *
     * <p>把循环从 {@code main} 中抽出并接收显式的流参数，是为了能在单元测试里用
     * {@code ByteArrayInputStream} / {@code ByteArrayOutputStream} 验证编码与退出行为，
     * 无需启动真实进程。
     */
    static void run(Agent agent, BufferedReader reader, PrintStream out) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (QUIT_COMMAND.equalsIgnoreCase(line.trim())) {
                break;
            }
            out.println(agent.respond(line));
        }
        out.flush();
    }
}

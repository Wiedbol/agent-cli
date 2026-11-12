package com.agent;

import com.agent.session.Redactor;

import java.io.OutputStream;
import java.io.PrintStream;

/** 聊天期间收起角色日志，供用户按需查看最近一次执行。 */
final class ChatTrace {
    private static final int MAX_CHARS = 64 * 1024;
    private static final String WORKING = "正在处理…";

    private final PrintStream out;
    private final StringBuilder latest = new StringBuilder();
    private final PrintStream log;
    private boolean active;
    private boolean working;
    private boolean truncated;

    ChatTrace(PrintStream out) {
        this.out = out;
        this.log = new PrintStream(OutputStream.nullOutputStream()) {
            @Override
            public void println(String message) {
                if (active) { record(message); }
                else { ChatTrace.this.out.println(message); }
            }
        };
    }

    PrintStream log() { return log; }

    boolean active() { return active; }

    void begin(boolean interactive) {
        reset();
        active = true;
        working = interactive;
        if (working) {
            out.print(WORKING);
            out.flush();
        } else {
            out.println();
        }
    }

    void clearStatus() {
        if (working) {
            out.print("\r" + " ".repeat(WORKING.length() * 2) + "\r");
            out.flush();
            working = false;
        }
    }

    void finish() {
        clearStatus();
        active = false;
    }

    void reset() {
        latest.setLength(0);
        truncated = false;
    }

    void show() {
        if (latest.isEmpty()) {
            out.println("当前聊天暂无执行过程。");
            return;
        }
        out.println("最近一次执行过程");
        out.print(latest);
        if (truncated) { out.println("执行过程过长，部分记录已省略。"); }
    }

    private void record(String message) {
        String safe = Redactor.redact(message, message).text();
        String separator = System.lineSeparator();
        if (safe.length() + separator.length() > MAX_CHARS - latest.length()) {
            truncated = true;
            return;
        }
        latest.append(safe).append(separator);
    }
}

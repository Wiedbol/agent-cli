package com.agent;

import java.io.PrintStream;

/** 保留列表标题的对齐方式，统一缩进每条记录的多行详情。 */
public final class ConsoleList {
    private final PrintStream out;
    private final boolean indented;
    private boolean started;

    public ConsoleList(PrintStream out, boolean indented) {
        this.out = out;
        this.indented = indented;
    }

    public void heading(String text) {
        if (indented && started) { out.println(); }
        out.println(text);
        started = true;
    }

    public void detail(String text) {
        if (indented) {
            text.lines().forEach(line -> out.println(line.isEmpty() ? "" : "  " + line));
        } else {
            out.println(text);
        }
    }
}

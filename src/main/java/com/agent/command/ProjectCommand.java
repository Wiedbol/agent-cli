package com.agent.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** 只将格式明确的项目命令转换为进程参数。其余命令仍由用户审批。 */
public final class ProjectCommand {

    private static final Pattern SIMPLE_LINE = Pattern.compile("[ \\t]*[a-z0-9]+(?:[ \\t]+[\\p{L}\\p{N}._/-]+)*[ \\t]*");
    private static final Pattern RELATIVE_PATH = Pattern.compile("[\\p{L}\\p{N}_][\\p{L}\\p{N}._-]*(?:/[\\p{L}\\p{N}_][\\p{L}\\p{N}._-]*)*");
    private static final Pattern JAVA_CLASS = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*");
    private static final Pattern BATCH_PATH = Pattern.compile("[A-Za-z]:[\\\\\\p{L}\\p{N} _.()-]+\\.cmd", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MAVEN_GOALS = Set.of("compile", "test", "package", "verify");
    private static final Set<String> MAVEN_FLAGS = Set.of("-B", "-q", "-o", "-DskipTests");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private ProjectCommand() { }

    public static Optional<List<String>> resolve(Path root, String command) {
        if (!SIMPLE_LINE.matcher(command).matches()) { return Optional.empty(); }
        List<String> words = List.of(command.strip().split("[ \\t]+"));
        String tool = words.get(0);
        List<String> args = words.subList(1, words.size());
        try {
            Path project = root.toRealPath();
            boolean supported = switch (tool) {
                case "javac" -> version(args) || compilation(project, args);
                case "java" -> version(args) || javaRun(project, args);
                case "python", "python3" -> args.equals(List.of("--version")) || pythonRun(project, args);
                case "mvn" -> version(args) || mavenBuild(project, args);
                default -> false;
            };
            if (!supported) { return Optional.empty(); }
            Path executable = installedTool(project, tool);
            if (executable == null) { return Optional.empty(); }
            if (WINDOWS && tool.equals("mvn") && executable.toString().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                return windowsMaven(project, executable, args);
            }
            List<String> invocation = new ArrayList<>();
            invocation.add(executable.toString());
            invocation.addAll(args);
            return Optional.of(List.copyOf(invocation));
        } catch (IOException | SecurityException | IllegalArgumentException unavailable) {
            return Optional.empty();
        }
    }

    private static boolean version(List<String> args) {
        return args.equals(List.of("-version")) || args.equals(List.of("--version"));
    }

    private static boolean compilation(Path project, List<String> args) {
        if (args.size() >= 2 && args.get(0).equals("-encoding") && Set.of("UTF-8", "GBK").contains(args.get(1))) {
            args = args.subList(2, args.size());
        }
        return !args.isEmpty() && args.stream().allMatch(arg -> file(project, arg, ".java"));
    }

    private static boolean javaRun(Path project, List<String> args) {
        if (args.size() == 2 && args.get(0).equals("-jar")) { return file(project, args.get(1), ".jar"); }
        if (args.size() == 1) {
            String target = args.get(0);
            if (file(project, target, ".java")) { return true; }
            String classpath = System.getenv("CLASSPATH");
            return (classpath == null || classpath.isBlank() || classpath.equals(".")) && localClass(project, ".", target);
        }
        return args.size() == 3 && (args.get(0).equals("-cp") || args.get(0).equals("-classpath"))
                && localClass(project, args.get(1), args.get(2));
    }

    private static boolean localClass(Path project, String classpath, String className) {
        if (!JAVA_CLASS.matcher(className).matches()) { return false; }
        String prefix = classpath.equals(".") ? "" : classpath + "/";
        return file(project, prefix + className.replace('.', '/') + ".class", ".class");
    }

    private static boolean pythonRun(Path project, List<String> args) {
        if (args.size() == 1) { return file(project, args.get(0), ".py"); }
        return args.equals(List.of("-m", "unittest")) || args.equals(List.of("-m", "unittest", "discover"))
                || args.equals(List.of("-m", "pytest")) || args.equals(List.of("-m", "pytest", "-q"));
    }

    private static boolean mavenBuild(Path project, List<String> args) {
        return !args.isEmpty() && args.stream().filter(MAVEN_GOALS::contains).count() == 1
                && args.stream().allMatch(arg -> MAVEN_GOALS.contains(arg) || MAVEN_FLAGS.contains(arg))
                && file(project, "pom.xml", ".xml");
    }

    private static boolean file(Path project, String relative, String suffix) {
        if (!relative.endsWith(suffix) || !RELATIVE_PATH.matcher(relative).matches()) { return false; }
        Path candidate = project.resolve(relative);
        try {
            // 核对真实路径，避免链接或 Windows 目录联接把相对路径引到别处。
            return Files.isRegularFile(candidate) && candidate.toRealPath().equals(candidate);
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static Path installedTool(Path project, String tool) throws IOException {
        if (tool.equals("java") || tool.equals("javac")) {
            Path jdk = Path.of(System.getProperty("java.home"), "bin");
            Path executable = installedFile(project, jdk.resolve(tool + (WINDOWS ? ".exe" : "")));
            if (executable != null) { return executable; }
        }
        List<String> names = !WINDOWS ? List.of(tool)
                : tool.equals("mvn") ? List.of("mvn.exe", "mvn.cmd") : List.of(tool + ".exe");
        return findOnPath(project, names, System.getenv("PATH"));
    }

    static Path findOnPath(Path project, List<String> names, String path) throws IOException {
        if (path == null) { return null; }
        for (String entry : path.split(Pattern.quote(java.io.File.pathSeparator))) {
            Path directory;
            try {
                directory = Path.of(entry);
            } catch (InvalidPathException ignored) {
                continue;
            }
            if (!directory.isAbsolute()) { continue; }
            for (String name : names) {
                Path executable = installedFile(project, directory.resolve(name));
                if (executable != null) { return executable; }
            }
        }
        return null;
    }

    private static Path installedFile(Path project, Path candidate) throws IOException {
        if (!candidate.isAbsolute() || candidate.normalize().startsWith(project) || !Files.isRegularFile(candidate)) {
            return null;
        }
        Path real = candidate.toRealPath();
        return !real.startsWith(project) && Files.isExecutable(real) ? real : null;
    }

    private static Optional<List<String>> windowsMaven(Path project, Path script, List<String> args) throws IOException {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || !BATCH_PATH.matcher(script.toString()).matches()) { return Optional.empty(); }
        Path shell = installedFile(project, Path.of(systemRoot, "System32", "cmd.exe"));
        if (shell == null) { return Optional.empty(); }
        // 批处理需要系统 cmd。这里只拼接已限制字符的安装路径和固定 Maven 参数。
        return Optional.of(List.of(shell.toString(), "/d", "/s", "/v:off", "/c",
                "call \"" + script + "\" " + String.join(" ", args)));
    }
}

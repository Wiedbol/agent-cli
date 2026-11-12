package com.agent.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCommandTest {

    @TempDir Path root;

    @Test
    void shouldResolveJavaFromTheInstallationWithExactProjectArguments() throws Exception {
        for (String name : List.of("Echo.java", "java", "java.exe", "javac.exe")) {
            Files.createFile(root.resolve(name));
        }
        Path classes = Files.createDirectories(root.resolve("target/classes/sample"));
        Files.createFile(classes.resolve("Echo.class"));

        List<String> compilation = ProjectCommand.resolve(root, "javac -encoding UTF-8 Echo.java").orElseThrow();
        assertEquals(List.of("-encoding", "UTF-8", "Echo.java"), compilation.subList(1, compilation.size()));
        assertFalse(Path.of(compilation.get(0)).toRealPath().startsWith(root.toRealPath()));
        List<String> execution = ProjectCommand.resolve(root, "java -cp target/classes sample.Echo").orElseThrow();
        assertEquals(List.of("-cp", "target/classes", "sample.Echo"), execution.subList(1, execution.size()));
        assertFalse(Path.of(execution.get(0)).toRealPath().startsWith(root.toRealPath()));
    }

    @Test
    void shouldSkipInvalidPathEntriesAndProjectLocalExecutables() throws Exception {
        Path project = Files.createDirectory(root.resolve("project")).toRealPath();
        Path installed = Files.createDirectory(root.resolve("tools")).toRealPath();
        Files.createFile(project.resolve("tool.exe")).toFile().setExecutable(true);
        Path executable = Files.createFile(installed.resolve("tool.exe"));
        executable.toFile().setExecutable(true);
        String path = String.join(java.io.File.pathSeparator, "\0invalid", project.toString(), installed.toString());

        assertEquals(executable, ProjectCommand.findOnPath(project, List.of("tool.exe"), path));
    }

    @Test
    void shouldRequireApprovalForUnknownOptionsAndShellSyntax() throws Exception {
        for (String name : List.of("Echo.java", "-mexample.py", "pom.xml")) { Files.createFile(root.resolve(name)); }
        for (String command : List.of("javac Echo.java && java Echo.java", "java Echo.java | more",
                "java Echo.java > result.txt", "java Echo.java\n", "java $SOURCE", "java %SOURCE%",
                "javac @sources.txt", "javac -d target Echo.java", "java -cp .. sample.Echo",
                "java ../Echo.java", "java Missing.java", "python -mexample.py", "python -c 'print(1)'",
                "mvn clean test", "mvn install", "mvn deploy", "mvn -f other.xml test", "git push")) {
            assertTrue(ProjectCommand.resolve(root, command).isEmpty(), command);
        }
    }
}

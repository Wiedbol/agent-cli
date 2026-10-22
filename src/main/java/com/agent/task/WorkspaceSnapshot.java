package com.agent.task;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** 保存输入内容版本；构建产物和 Agent 自身落盘不应使验证立即过期。 */
public final class WorkspaceSnapshot {
    private WorkspaceSnapshot() { }

    public static Map<String, String> capture(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName().toString();
                boolean metadata = dir.equals(root.resolve(".agent")) || name.equals(".git");
                boolean generated = name.equals("target") && (dir.getParent().equals(root)
                        || Files.isRegularFile(dir.getParent().resolve("pom.xml")));
                if (!dir.equals(root) && (metadata || generated)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                requireInside(root, dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                requireInside(root, file);
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("任务输入不支持符号链接: " + file.getFileName());
                }
                if (attrs.isRegularFile()) {
                    files.put(root.relativize(file).toString().replace('\\', '/'), hash(Files.readAllBytes(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    public static void requireInside(Path root, Path path) throws IOException {
        if (!path.toRealPath().startsWith(root)) {
            throw new IOException("路径越出实际项目: " + path.getFileName());
        }
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

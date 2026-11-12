package com.agent.experience;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 共享索引只保留来源项目路径，读取时仍核实原有经验和来源证据。 */
final class SharedExperiences {
    private final Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    private final Path directory = home.resolve(".agent-cli/shared-experiences");

    void share(String id, Path owner) throws IOException {
        Path entry = entry(id);
        Files.createDirectories(directory);
        checkPath(entry);
        Path temporary = Files.createTempFile(directory, "share-", ".tmp");
        try {
            Files.writeString(temporary, owner.toString());
            try {
                Files.move(temporary, entry);
            } catch (FileAlreadyExistsException existing) {
                requireOwner(entry, owner);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    boolean unshare(String id, Path owner) throws IOException {
        Path entry = entry(id);
        if (Files.notExists(entry, LinkOption.NOFOLLOW_LINKS)) { return false; }
        requireOwner(entry, owner);
        return Files.deleteIfExists(entry);
    }

    Experience read(String id) throws IOException {
        var owner = new ExperienceLibrary(owner(entry(id)));
        Experience experience = owner.read(id);
        if (!experience.enabled() || !owner.sourceValid(experience)) {
            throw new IOException("共享经验已停用或来源不可用: " + id);
        }
        return experience;
    }

    List<Experience> available() throws IOException {
        checkPath(directory);
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) { return List.of(); }
        try (var files = Files.list(directory)) {
            var values = new ArrayList<Experience>();
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".ref")) { continue; }
                try { values.add(read(name.substring(0, name.length() - 4))); }
                catch (IOException invalid) { /* 单条记录失效时其余经验仍可用。 */ }
            }
            return List.copyOf(values);
        }
    }

    private Path entry(String id) throws IOException {
        ExperienceLibrary.requireId(id);
        Path entry = directory.resolve(id + ".ref");
        checkPath(entry);
        return entry;
    }

    private Path owner(Path entry) throws IOException {
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.size(entry) > 32_768) {
            throw new IOException("共享经验不存在或索引无效: " + entry.getFileName());
        }
        try {
            Path path = Path.of(Files.readString(entry));
            if (!path.isAbsolute() || !path.equals(path.normalize())) { throw new IOException("共享经验来源路径无效"); }
            return path;
        } catch (InvalidPathException invalid) {
            throw new IOException("共享经验来源路径无效", invalid);
        }
    }

    private void requireOwner(Path entry, Path owner) throws IOException {
        if (!owner.equals(owner(entry))) { throw new IOException("请在共享经验的原项目执行此操作"); }
    }

    private void checkPath(Path path) throws IOException {
        Path realHome = home.toRealPath();
        for (Path part = path; !part.equals(home); part = part.getParent()) {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(part) || !part.toRealPath().startsWith(realHome))) {
                throw new IOException("共享经验索引路径无效");
            }
        }
    }
}

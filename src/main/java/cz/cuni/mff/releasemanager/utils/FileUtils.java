package cz.cuni.mff.releasemanager.utils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.UUID;

// file operations shared by installers, backups, and providers
public class FileUtils {
    // temp directory plus a validated filename inside it
    public static Path createTempFilePath(String filename) throws IOException {
        validateFilename(filename);
        Path dir = Files.createTempDirectory("github-release-manager-");
        return dir.resolve(filename);
    }

    private static void validateFilename(String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            throw new IOException("Invalid filename: " + filename);
        }
        try {
            Path name = Path.of(filename);
            if (name.isAbsolute()
                || name.getNameCount() != 1
                || filename.equals(".")
                || filename.equals("..")
                || filename.contains("/")
                || filename.contains("\\")) {
                throw new IOException("Invalid filename: " + filename);
            }
        } catch (InvalidPathException e) {
            throw new IOException("Invalid filename: " + filename, e);
        }
    }
    // provider-qualified install name; a hash separates "__" lookalike paths
    public static String installName(String provider, String repoFullName) {
        String qualified = provider == null || provider.isBlank()
            ? repoFullName
            : provider + "__" + repoFullName;
        String base = qualified == null ? "" : qualified.replace("/", "__");
        String name = base.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[._-]+|[._-]+$", "")
            .replaceAll("-{2,}", "-");
        if (name.isBlank() || isReservedWindowsName(name)) {
            name = "app-" + Integer.toUnsignedString(base.hashCode(), 16);
        }
        if (repoFullName != null && repoFullName.contains("__")) {
            name += "--" + Integer.toUnsignedString(qualified.hashCode(), 16);
        }
        return name;
    }

    private static boolean isReservedWindowsName(String name) {
        return switch (name) {
            case "con", "prn", "aux", "nul" -> true;
            default -> name.matches("com[1-9]|lpt[1-9]");
        };
    }

    // delete the parent when it is empty
    public static void removeTempDir(Path file) {
        try {
            Path dir = file.getParent();
            if (dir == null || !Files.isDirectory(dir)) {
                return;
            }
            try (var entries = Files.list(dir)) {
                if (entries.findAny().isEmpty()) {
                    Files.delete(dir);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to remove directory: " + e.getMessage());
        }
    }

    // swap in the staged file, restoring the old one on failure
    public static void replaceFile(Path source, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Path backup = null;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            backup = Files.createTempFile(parent, ".ape-get-backup-", ".tmp");
            try {
                Files.copy(target, backup,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException e) {
                Files.deleteIfExists(backup);
                throw e;
            }
        }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            if (backup != null) {
                try {
                    Files.deleteIfExists(backup);
                } catch (IOException e) {
                    System.err.println(
                        "Warning: could not remove backup " + backup + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (backup != null) {
                try {
                    Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException restore) {
                    e.addSuppressed(restore);
                }
            }
            throw e;
        }
    }

    // swap in the staged directory, restoring the old one on failure
    public static void replaceDirectory(Path source, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Path backup = parent.resolve(
            "." + target.getFileName() + ".ape-get-backup-" + UUID.randomUUID());
        boolean movedOld = false;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                move(target, backup);
                movedOld = true;
            }
            move(source, target);
        } catch (IOException e) {
            if (movedOld && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    move(backup, target);
                } catch (IOException restore) {
                    e.addSuppressed(restore);
                }
            }
            throw e;
        }
        if (movedOld) {
            try {
                deleteRecursively(backup);
            } catch (IOException e) {
                System.err.println("Warning: could not remove backup " + backup + ": " + e.getMessage());
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    // copy a tree without following symlinks
    public static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(
                    file,
                    target.resolve(source.relativize(file)),
                    StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // delete a tree without following symlinks
    public static void deleteRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

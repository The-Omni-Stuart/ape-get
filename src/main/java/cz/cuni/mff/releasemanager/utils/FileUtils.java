package cz.cuni.mff.releasemanager.utils;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for file operations.
 */
public class FileUtils {
    /**
     * Saves an InputStream to a file with the given filename in a temporary directory.
     * @param stream
     * @param filename
     * @return path to the saved file
     * @throws IOException if the filename is invalid, the copy fails, or the file is empty
    */
    public static Path saveInputStreamToFile(InputStream stream, String filename) throws IOException {
        Path destination = createTempFilePath(filename);
        Path dir = destination.getParent();
        try {
            Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(destination) == 0) {
                throw new IOException("Downloaded file is empty: " + filename);
            }
            return destination.toAbsolutePath();
        } catch (IOException e) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
                // preserve the original failure
            }
            try {
                Files.deleteIfExists(dir);
            } catch (IOException ignored) {
                // preserve the original failure
            }
            throw e;
        }
    }

    /**
     * Creates a temporary directory and a validated destination path inside it.
     * The file itself is not created.
     * @param filename asset filename
     * @return destination path
     * @throws IOException if the filename is invalid
     */
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
    /**
     * Derives a stable, non-empty install name from a repository, falling back to the asset name.
     * @param repoFullName Repository in owner/name form, or null.
     * @param asset Downloaded asset path, used only as a fallback.
     * @return a safe install name
     */
    public static String getInstallName(String repoFullName, Path asset) {
        String base = repoFullName == null ? "" : repoFullName.replace("/", "__");
        if (base.isBlank()) {
            String fileName = asset == null ? "" : asset.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            base = dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        String name = base.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[._-]+|[._-]+$", "")
            .replaceAll("-{2,}", "-");
        if (name.isBlank() || isReservedWindowsName(name)) {
            name = "app-" + Integer.toUnsignedString(base.hashCode(), 16);
        }
        return name;
    }

    private static boolean isReservedWindowsName(String name) {
        return switch (name) {
            case "con", "prn", "aux", "nul" -> true;
            default -> name.matches("com[1-9]|lpt[1-9]");
        };
    }

    /**
     * Creates a short name for the given file
     * @param file Path to the file
     * @return Path to the short name
     */
    public static String getShortCut(Path file) {
        String fileName = file.getFileName().toString();
        Pattern pattern = Pattern.compile("^[A-Za-z]+");
        Matcher matcher = pattern.matcher(fileName);

        return matcher.find() ? matcher.group().toLowerCase() : "";
    }

    /**
     * Removes the parent temporary directory if it is empty.
     * @param file Path to the file
     */
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

    /**
     * Replaces a target file with a staged file, keeping a temporary backup for rollback.
     * @param source staged file
     * @param target live file
     * @throws IOException if the replace or restore fails
     */
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

    /**
     * Replaces a target directory with a staged directory, keeping a temporary backup for rollback.
     * @param source staged directory
     * @param target live directory
     * @throws IOException if the swap or restore fails
     */
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

    /**
     * Deletes a file or directory tree without following symbolic links.
     * @param path Path to delete
     * @throws IOException if any entry cannot be deleted
     */
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

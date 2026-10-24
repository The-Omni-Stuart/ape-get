package cz.cuni.mff.releasemanager.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
        validateFilename(filename);
        Path dir = Files.createTempDirectory("github-release-manager-");
        Path destination = dir.resolve(filename);
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
     * Creates a directory with the given name if it does not already exist.
     * @param directoryName
     * @return Path to the created directory
     * @throws IOException
     */
    public static Path createDirectory(String directoryName) throws IOException {
        Path path = Paths.get(directoryName);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        return path;
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
            System.out.println("Failed to remove directory: " + e.getMessage());
        }
    }
}

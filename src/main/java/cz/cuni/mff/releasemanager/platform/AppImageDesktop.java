package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.ProcessRunner;

// pulls the embedded desktop file and icon out of an AppImage
final class AppImageDesktop {

    private static final long MAX_ICON_BYTES = 5 * 1024 * 1024;
    private static final Duration EXTRACT_TIMEOUT = Duration.ofSeconds(30);

    private AppImageDesktop() {}

    static Optional<Metadata> read(Path appImage) {
        return read(appImage, EXTRACT_TIMEOUT);
    }

    static Optional<Metadata> read(Path appImage, Duration timeout) {
        Path fullDir = null;
        try {
            fullDir = Files.createTempDirectory("grm-desktop-");
            if (!extract(appImage, fullDir, timeout)) {
                return Optional.empty();
            }
            Path desktopFile;
            try (Stream<Path> paths = Files.walk(fullDir)) {
                desktopFile = paths
                    .filter(path -> path.getFileName().toString().endsWith(".desktop"))
                    .filter(path -> path.toString().contains("squashfs-root"))
                    .findFirst()
                    .orElse(null);
            }
            if (desktopFile == null) {
                return Optional.empty();
            }
            Map<String, String> entries = parse(desktopFile);
            String iconName = entries.get("Icon");
            Path icon = findIcon(fullDir, iconName);
            if (icon == null) {
                return Optional.of(new Metadata(entries, null, null));
            }
            long size = Files.size(icon);
            if (size <= 0 || size > MAX_ICON_BYTES) {
                return Optional.of(new Metadata(entries, null, null));
            }
            String extension = extension(icon);
            return Optional.of(new Metadata(
                entries, Files.readAllBytes(icon), extension));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Could not read AppImage desktop data: " + e.getMessage());
            return Optional.empty();
        } finally {
            delete(fullDir);
        }
    }

    private static boolean extract(
        Path appImage, Path directory, Duration timeout)
        throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder()
            .command(appImage.toString(), "--appimage-extract")
            .directory(directory.toFile());
        ProcessRunner.Result result = ProcessRunner.run(builder, timeout);
        return !result.timedOut() && result.exitCode() == 0;
    }

    static Map<String, String> parse(Path desktopFile) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        boolean inDesktopEntry = false;
        for (String line : Files.readAllLines(desktopFile, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("[")) {
                inDesktopEntry = "[Desktop Entry]".equals(trimmed);
                continue;
            }
            if (!inDesktopEntry) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals > 0) {
                entries.put(line.substring(0, equals).strip(),
                    line.substring(equals + 1).strip());
            }
        }
        return entries;
    }

    static Path findIcon(Path root, String iconName) throws IOException {
        if (iconName == null || iconName.isBlank()) {
            return null;
        }
        String rawBase = Path.of(iconName).getFileName().toString().toLowerCase(Locale.ROOT);
        String base = rawBase.contains(".")
            ? rawBase.substring(0, rawBase.lastIndexOf('.')) : rawBase;
        List<String> extensions = List.of(".png", ".svg", ".xpm");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!name.startsWith(base)) {
                        return false;
                    }
                    return extensions.stream().anyMatch(name::endsWith);
                })
                .filter(path -> path.toString().contains("apps"))
                .sorted(Comparator.comparingInt(
                    path -> path.getFileName().toString().length()))
                .findFirst()
                .orElse(null);
        }
    }

    private static String extension(Path icon) {
        String name = icon.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ".png" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            FileUtils.deleteRecursively(directory);
        } catch (IOException ignored) {
            // best-effort temporary cleanup
        }
    }

    record Metadata(Map<String, String> entries, byte[] icon, String iconExtension) {}
}

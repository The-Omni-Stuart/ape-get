package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import cz.cuni.mff.releasemanager.platform.AssetSelector;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

/**
 * rngit releases over Reticulum. The CLI verifies the signed release manifest.
 */
public final class RngitProvider implements ReleaseProvider {
    private static final String ASSET_PREFIX = "rngit://";

    private final ProviderConfig config;
    private final RngitCommand command;
    private final Path workDir;

    public RngitProvider(ProviderConfig config) {
        this(config, new ProcessRngitCommand(),
            Path.of(System.getProperty("java.io.tmpdir")));
    }

    RngitProvider(ProviderConfig config, RngitCommand command) {
        this(config, command, Path.of(System.getProperty("java.io.tmpdir")));
    }

    RngitProvider(ProviderConfig config, RngitCommand command, Path workDir) {
        this.config = config;
        this.command = command;
        this.workDir = workDir;
    }

    @Override
    public ProviderConfig config() {
        return config;
    }

    @Override
    public ReleaseAssets latest(String path) throws IOException, InterruptedException {
        return view(path, "latest");
    }

    @Override
    public ReleaseAssets release(String path, String tag)
        throws IOException, InterruptedException {
        requireTag(tag);
        return view(path, tag);
    }

    @Override
    public Path download(Asset asset) throws IOException, InterruptedException {
        Ref ref = parseAssetUrl(asset.url());
        Path temp = Files.createTempDirectory("grm-rngit-");
        try {
            String target = ref.tag() + ":" + ref.name();
            RngitCommand.Result result = command.run(
                List.of("release", repositoryUrl(ref.path()), "fetch", target),
                temp, NetworkSettings.rngitFetchTimeout());
            if (result.exitCode() != 0) {
                throw failure("fetch", ref.name(), result.output());
            }
            Path downloaded = findArtifact(temp, ref.name());
            if (downloaded == null) {
                throw new IOException("rngit did not produce " + ref.name());
            }
            Path staged = StagedAsset.create(ref.name());
            try {
                Files.copy(downloaded, staged, StandardCopyOption.REPLACE_EXISTING);
                StagedAsset.requireContent(staged, ref.name());
                return staged;
            } catch (IOException e) {
                StagedAsset.discard(staged);
                throw e;
            }
        } finally {
            // temp dir cleanup must not hide the fetch error
            try {
                FileUtils.deleteRecursively(temp);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public SearchResult search(String query) throws IOException {
        throw new ProviderException(
            ProviderException.Reason.UNSUPPORTED,
            "repository search is not supported");
    }

    private ReleaseAssets view(String path, String target)
        throws IOException, InterruptedException {
        String repository = repositoryUrl(path);
        RngitCommand.Result result = command.run(
            List.of("release", repository, "view", target),
            workDir, NetworkSettings.rngitMetadataTimeout());
        if (result.exitCode() != 0) {
            throw failure("read", target, result.output());
        }
        String tag = parseTag(result.output());
        if (tag == null) {
            throw new IOException("rngit returned no release for " + target);
        }
        List<Asset> all = new ArrayList<>();
        for (String name : parseArtifacts(result.output())) {
            all.add(new Asset(assetUrl(path, tag, name), name));
        }
        List<Asset> installable = AssetSelector.installable(
            all,
            Platform.getPlatformHandler().getFormats(),
            Platform.detectPlatform().getArchitecture());
        return new ReleaseAssets(tag, null, all, installable);
    }

    static String parseTag(String output) {
        for (String line : output.split("\\R")) {
            String clean = stripAnsi(line);
            int marker = clean.indexOf("Release :");
            if (marker >= 0) {
                String value = clean.substring(marker + "Release :".length()).strip();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    static List<String> parseArtifacts(String output) {
        List<String> names = new ArrayList<>();
        boolean inArtifacts = false;
        for (String line : output.split("\\R")) {
            String clean = stripAnsi(line).strip();
            if (clean.startsWith("Artifacts (")) {
                inArtifacts = true;
                continue;
            }
            if (!inArtifacts || !clean.startsWith("-")) {
                continue;
            }
            String name = clean.substring(1).strip();
            int size = name.lastIndexOf(" (");
            if (size > 0) {
                name = name.substring(0, size).strip();
            }
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private String repositoryUrl(String path) throws IOException {
        String[] parts = requireRepositoryPath(path);
        String base = config.resolvedApi();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + parts[0] + "/" + parts[1];
    }

    private static String[] requireRepositoryPath(String path) throws IOException {
        String[] parts = path == null ? new String[0] : path.split("/", -1);
        if (parts.length != 2
            || !validSegment(parts[0]) || !validSegment(parts[1])) {
            throw new IOException(
                "rngit repositories use group/repo paths, got: " + path);
        }
        return parts;
    }

    private static boolean validSegment(String value) {
        return !value.isBlank() && !".".equals(value) && !"..".equals(value);
    }

    private static void requireTag(String tag) throws IOException {
        if (tag == null || tag.isBlank() || tag.startsWith("-")
            || tag.contains("/") || tag.contains(":") || hasControl(tag)) {
            throw new IOException("Invalid rngit release tag: " + tag);
        }
    }

    private static void requireArtifactName(String name) throws IOException {
        if (name == null || name.isBlank()
            || name.contains("/") || name.contains("\\") || name.contains(":")
            || hasControl(name)
            || ".".equals(name) || "..".equals(name)) {
            throw new IOException("Invalid rngit artifact name: " + name);
        }
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static String assetUrl(String path, String tag, String name) {
        String raw = path + "\0" + tag + "\0" + name;
        return ASSET_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Ref parseAssetUrl(String url) throws IOException {
        if (url == null || !url.startsWith(ASSET_PREFIX)) {
            throw new IOException("Invalid rngit asset URL");
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder()
                .decode(url.substring(ASSET_PREFIX.length())), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid rngit asset URL", e);
        }
        String[] parts = raw.split("\0", 3);
        if (parts.length != 3) {
            throw new IOException("Invalid rngit asset URL");
        }
        Ref ref = new Ref(parts[0], parts[1], parts[2]);
        requireRepositoryPath(ref.path());
        requireTag(ref.tag());
        requireArtifactName(ref.name());
        return ref;
    }

    private static Path findArtifact(Path directory, String name) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals(name))
                .findFirst()
                .orElse(null);
        }
    }

    private static ProviderException failure(
        String action, String target, String output) {
        String message = "rngit could not " + action + " " + target + ": "
            + summarize(output) + pathHint(output);
        String lower = output == null
            ? "" : output.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("could not resolve path")
            || lower.contains("link establishment failed")
            || lower.contains("could not recall")) {
            return new ProviderException(
                ProviderException.Reason.UNREACHABLE, message);
        }
        if (lower.contains("not found") || lower.contains("no releases")) {
            return new ProviderException(
                ProviderException.Reason.NOT_FOUND, message);
        }
        return new ProviderException(ProviderException.Reason.OTHER, message);
    }

    // rngit starts its own instance; a missing path usually means no interfaces
    private static String pathHint(String output) {
        return output != null && output.contains("Could not resolve path")
            ? " (check the Reticulum interfaces or start rnsd)" : "";
    }

    private static String summarize(String output) {
        if (output == null) {
            return "no output";
        }
        String last = "";
        for (String line : output.split("\\R")) {
            String clean = stripAnsi(line).strip();
            if (!clean.isEmpty()) {
                last = clean;
            }
        }
        if (last.isEmpty()) {
            return "no output";
        }
        return last.length() > 300 ? last.substring(0, 300) + "..." : last;
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private record Ref(String path, String tag, String name) {}
}

package cz.cuni.mff.releasemanager.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.ChildEnvironment;
import cz.cuni.mff.releasemanager.utils.Executables;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.ProcessRunner;

// verifies the code signature on the app bundle inside a DMG
public final class MacSignatureVerifier implements SignatureVerifier {
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Override
    public List<Tool> tools() {
        return List.of(
            new Tool("codesign", true, Executables.isAvailable("codesign")),
            new Tool("hdiutil", true, Executables.isAvailable("hdiutil")),
            new Tool("spctl", false, Executables.isAvailable("spctl")));
    }

    @Override
    public SignatureResult verify(
        ReleaseProvider provider, Asset target, List<Asset> releaseAssets, Path downloaded)
        throws IOException, InterruptedException {
        String name = downloaded.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".dmg")) {
            return SignatureResult.unavailable(
                target.name(), "code signature verification applies to .dmg only");
        }

        Path mount = Files.createTempDirectory("grm-verify-");
        boolean attached = false;
        try {
            ProcessRunner.Result attach = run("hdiutil", "attach", "-nobrowse", "-readonly",
                "-mountpoint", mount.toString(), downloaded.toString());
            if (attach.exitCode() != 0) {
                return SignatureResult.error(
                    target.name(), "could not mount disk image: " + firstLine(attach.output()));
            }
            attached = true;

            Optional<Path> app = findApp(mount);
            if (app.isEmpty()) {
                return SignatureResult.invalid(
                    target.name(), "codesign", "no application bundle found");
            }

            ProcessRunner.Result verify = run(
                "codesign", "--verify", "--deep", "--strict", app.get().toString());
            if (verify.exitCode() != 0) {
                return SignatureResult.invalid(
                    target.name(), "codesign", firstLine(verify.output()));
            }

            ProcessRunner.Result details = run(
                "codesign", "-dv", "--verbose=4", app.get().toString());
            String team = value(details.output(), "TeamIdentifier=");
            if (team != null && team.equalsIgnoreCase("not set")) {
                team = null;
            }
            String authority = value(details.output(), "Authority=");
            Boolean notarized = null;
            try {
                ProcessRunner.Result assessment = run(
                    "spctl", "--assess", "--type", "execute", "--verbose=2",
                    app.get().toString());
                notarized = assessment.exitCode() == 0;
            } catch (IOException e) {
                // spctl is best-effort; codesign already verified the bundle
            }
            return SignatureResult.verified(
                target.name(),
                "codesign",
                authority == null ? "ad-hoc" : authority,
                team,
                notarized);
        } catch (IOException e) {
            return SignatureResult.unavailable(
                target.name(), "codesign or hdiutil is not available");
        } finally {
            if (attached) {
                runQuietly("hdiutil", "detach", mount.toString(), "-force");
            }
            try {
                FileUtils.deleteRecursively(mount);
            } catch (IOException ignored) {
                // the runner will clean up an undetachable mount
            }
        }
    }

    private static Optional<Path> findApp(Path mount) throws IOException {
        try (var paths = Files.walk(mount)) {
            return paths
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName() != null
                    && path.getFileName().toString().endsWith(".app"))
                .findFirst();
        }
    }

    private static ProcessRunner.Result run(String... command)
        throws IOException, InterruptedException {
        return ProcessRunner.run(
            ChildEnvironment.sanitize(new ProcessBuilder(command)), TIMEOUT);
    }

    private static void runQuietly(String... command) {
        try {
            run(command);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String value(String output, String prefix) {
        return output.lines()
            .filter(line -> line.startsWith(prefix))
            .map(line -> line.substring(prefix.length()).trim())
            .findFirst()
            .orElse(null);
    }

    private static String firstLine(String output) {
        return output.lines()
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse("no output");
    }
}

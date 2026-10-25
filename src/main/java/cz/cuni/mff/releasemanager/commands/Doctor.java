package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.json.JsonViews.DoctorReport;
import cz.cuni.mff.releasemanager.json.JsonViews.PinView;
import cz.cuni.mff.releasemanager.json.JsonViews.ToolView;
import cz.cuni.mff.releasemanager.platform.PathEnvironment;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.GrmIndex;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.DiskSpace;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.verify.SignatureVerifier;

// checks state, live installs, PATH, disk space, mounts, and signature tools
public final class Doctor {

    private static final long MIN_FREE_BYTES = 100L * 1024 * 1024;

    private final Platform.OS os;
    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private final PlatformHandler platformHandler;
    private final SignatureVerifier signatureVerifier;

    public Doctor(
        Platform.OS os,
        CmdParser cmdParser,
        StateStore stateStore,
        PlatformHandler platformHandler,
        SignatureVerifier signatureVerifier) {
        this.os = os;
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.platformHandler = platformHandler;
        this.signatureVerifier = signatureVerifier;
    }

    public boolean run() {
        boolean healthy = true;
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Path root = stateStore.paths().root();
        Path configFile = stateStore.paths().configFile();
        Path indexFile = stateStore.paths().indexFile();

        ProviderRegistry providers = null;
        if (!Files.exists(configFile)) {
            issues.add("Config: missing at " + configFile);
            healthy = false;
        } else {
            try {
                GrmConfig config = stateStore.loadConfig().config();
                applyInstallRoot(config);
                providers = new ProviderRegistry(config.providers());
            } catch (IOException e) {
                issues.add("Config: failed to read: " + e.getMessage());
                healthy = false;
            }
        }

        List<AppManifest> manifests = List.of();
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            issues.add("Manifests: failed to read: " + e.getMessage());
            healthy = false;
        }

        Optional<GrmIndex> index;
        try {
            index = stateStore.loadIndex();
        } catch (IOException e) {
            issues.add("Index: failed to read: " + e.getMessage());
            index = Optional.empty();
            healthy = false;
        }
        if (index.isEmpty()) {
            issues.add("Index: missing or corrupt (run a stateful command to rebuild)");
            healthy = false;
        } else {
            for (StateStore.RecoveryIssue issue
                : stateStore.findRecoveryIssues(index.get(), manifests)) {
                issues.add("Recovery: " + issue.kind() + " " + issue.entry().repo());
                healthy = false;
            }
        }

        for (AppManifest manifest : manifests) {
            PlatformHandler.InstallState state = platformHandler.installState(manifest);
            if (state == PlatformHandler.InstallState.MISSING) {
                issues.add("Missing live app: " + manifest.repo()
                    + " -> " + manifest.install().path());
                healthy = false;
            } else if (state == PlatformHandler.InstallState.UNKNOWN) {
                issues.add("Unknown live app state: " + manifest.repo()
                    + " -> " + manifest.install().path());
                healthy = false;
            }
        }

        Set<String> missingProviders = new HashSet<>();
        for (AppManifest manifest : manifests) {
            if (manifest.provider() != null
                && (providers == null || providers.get(manifest.provider()).isEmpty())) {
                missingProviders.add(manifest.provider());
            }
        }
        for (String provider : missingProviders) {
            issues.add("Provider not configured: " + provider
                + " (run providers recover " + provider + ")");
            healthy = false;
        }

        List<ToolView> tools = new ArrayList<>();
        for (SignatureVerifier.Tool tool : signatureVerifier.tools()) {
            tools.add(new ToolView(tool.name(), tool.required(), tool.available()));
            if (!tool.available()) {
                String prefix = tool.required()
                    ? "Signature tool missing: "
                    : "Optional signature tool missing: ";
                issues.add(prefix + tool.name());
                if (tool.required()) {
                    healthy = false;
                }
            }
        }

        List<PinView> pins = new ArrayList<>();
        for (AppManifest manifest : manifests) {
            if (manifest.signature() != null && manifest.signature().isVerified()) {
                pins.add(new PinView(
                    manifest.repo(),
                    manifest.signature().signer(),
                    manifest.signature().identity()));
            }
        }

        Path mountPoint = platformHandler.mountPoint();
        if (mountPoint != null && Files.isDirectory(mountPoint)) {
            try (var entries = Files.list(mountPoint)) {
                if (entries.findAny().isPresent()) {
                    issues.add("Stale mount: " + mountPoint + " is not empty");
                    healthy = false;
                }
            } catch (IOException e) {
                issues.add("Stale mount: cannot read " + mountPoint + ": " + e.getMessage());
                healthy = false;
            }
        }

        Path installRoot = platformHandler.getInstallRoot();
        long freeBytes = DiskSpace.usableSpace(installRoot == null ? root : installRoot);
        if (!DiskSpace.hasAtLeast(installRoot, MIN_FREE_BYTES)) {
            issues.add("Low disk space at " + installRoot + ": "
                + (freeBytes < 0 ? "unknown" : freeBytes / (1024 * 1024) + " MB")
                + " free, need " + MIN_FREE_BYTES / (1024 * 1024) + " MB");
            healthy = false;
        }
        if (os == Platform.OS.LINUX && !manifests.isEmpty()
            && installRoot != null && !PathEnvironment.isOnPath(installRoot)) {
            warnings.add("PATH: " + installRoot + " is not on PATH");
        }

        if (os == Platform.OS.LINUX) {
            List<String> orphans = orphanCandidates();
            if (!orphans.isEmpty()) {
                issues.addAll(orphans);
                healthy = false;
            }
        }

        if (cmdParser.isJson()) {
            Output.json(new DoctorReport(
                healthy,
                root.toString(),
                configFile.toString(),
                indexFile.toString(),
                manifests.size(),
                issues,
                warnings,
                tools,
                pins,
                freeBytes));
        } else {
            Output.data("State root: " + root);
            Output.data("Config: " + configFile);
            Output.data("Index: " + indexFile);
            Output.data("Manifests: " + manifests.size());
            Output.data("Free space: "
                + (freeBytes < 0 ? "unknown" : freeBytes / (1024 * 1024) + " MB"));
            for (ToolView tool : tools) {
                Output.data("Signature tool: " + tool.name() + " "
                    + (tool.available() ? "available" : "missing"));
            }
            for (PinView pin : pins) {
                Output.data("Pinned signer: " + pin.repo() + " -> "
                    + pin.signer() + " (" + pin.identity() + ")");
            }
            for (String warning : warnings) {
                Output.data("Warning: " + warning);
            }
            for (String issue : issues) {
                Output.data(issue);
            }
        }
        return healthy;
    }

    private void applyInstallRoot(GrmConfig config) {
        String root = config.installRoot();
        if (root == null || root.isBlank()) {
            return;
        }
        try {
            platformHandler.setInstallRoot(Path.of(root));
        } catch (InvalidPathException e) {
            Output.error("Ignoring invalid install_root: " + root);
        }
    }

    private List<String> orphanCandidates() {
        Path liveRoot = platformHandler.getInstallRoot();
        if (liveRoot == null || !Files.isDirectory(liveRoot)) {
            return List.of();
        }
        List<String> orphans = new ArrayList<>();
        try (var files = Files.list(liveRoot)) {
            List<AppManifest> manifests = stateStore.scanManifests();
            // Linux launchers are symlinks; other files in the bin are not ours
            for (Path file : files.filter(Files::isSymbolicLink).toList()) {
                String name = file.getFileName().toString();
                boolean tracked = manifests.stream().anyMatch(manifest ->
                    FileUtils.installName(manifest.provider(), manifest.repo())
                        .equals(name));
                if (tracked) {
                    if (Files.isSymbolicLink(file) && !Files.exists(file)) {
                        orphans.add("Broken launcher: " + file);
                    }
                    continue;
                }
                orphans.add("Orphan candidate: " + file);
            }
        } catch (IOException e) {
            return List.of("Failed to scan " + liveRoot + ": " + e.getMessage());
        }
        return orphans;
    }
}

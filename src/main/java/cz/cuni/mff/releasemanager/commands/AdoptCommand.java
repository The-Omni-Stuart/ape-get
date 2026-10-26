package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.install.ManifestFactory;
import cz.cuni.mff.releasemanager.json.JsonViews.AdoptedView;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ManifestStore;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.RepoParser;

// takes ownership of a binary that was installed outside this tool
public final class AdoptCommand {
    private final Platform.OS os;
    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private final ManifestStore manifests;
    private final PlatformHandler platformHandler;
    private final ProviderRegistry providers;
    private final Confirmer confirmer;

    public AdoptCommand(
        Platform.OS os,
        CmdParser cmdParser,
        StateStore stateStore,
        ManifestStore manifests,
        PlatformHandler platformHandler,
        ProviderRegistry providers,
        Confirmer confirmer) {
        this.os = os;
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.manifests = manifests;
        this.platformHandler = platformHandler;
        this.providers = providers;
        this.confirmer = confirmer;
    }

    public boolean run(Command command) {
        if (os != Platform.OS.LINUX) {
            Output.error("adopt is currently supported on Linux only.");
            return false;
        }
        RepoRef ref = RepoParser.parseOrNull(command.argument);
        if (ref == null) {
            return false;
        }
        Path liveRoot = platformHandler.getInstallRoot();
        if (liveRoot == null) {
            Output.error("Cannot determine the install root.");
            return false;
        }
        Path live = liveRoot.resolve(FileUtils.installName(ref.provider(), ref.path()));
        if (!Files.isRegularFile(live)) {
            Output.error("No installed file found at " + live + ".");
            return false;
        }
        try {
            if (stateStore.findManifest(ref).isPresent()) {
                Output.error("Release " + ref.display() + " is already tracked.");
                return false;
            }
        } catch (IOException e) {
            Output.error("Failed to read application state: " + e.getMessage());
            return false;
        }

        String message = "Adopt " + live + " as " + ref.display()
            + "?\nVersion and checksum are unknown.";
        if (!confirmer.confirm(message)) {
            Output.error("Adoption declined.");
            return false;
        }

        try {
            String name = live.getFileName().toString();
            ProviderConfig providerConfig = providers.get(ref.provider()).orElse(null);
            AppManifest manifest = new AppManifest(
                AppManifest.KIND,
                AppManifest.SCHEMA_VERSION,
                ref.provider(),
                ref.path(),
                null,
                null,
                new AppManifest.Asset(name, live.toUri().toString(), null),
                new AppManifest.Install(
                    ManifestFactory.installKind(name),
                    live.toString(),
                    stateStore.paths().appDir(ref.provider(), ref.path()).toString(),
                    new AppManifest.Uninstall(live.toString(), List.of(), null)),
                new AppManifest.Verification("unknown", "none", null, null, null),
                List.of(),
                Files.getLastModifiedTime(live).toInstant(),
                Instant.now(),
                null,
                providerConfig == null ? null : providerConfig.type(),
                providerConfig == null ? null : providerConfig.resolvedApi());
            if (!manifests.save(manifest)) {
                return false;
            }
            if (cmdParser.isJson()) {
                Output.json(new AdoptedView(
                    new ManifestViews(platformHandler).listEntry(manifest)));
            } else {
                Output.info("Adopted " + ref.display() + " at " + live + ".");
            }
            return true;
        } catch (IOException e) {
            Output.error("Failed to adopt " + ref.display() + ": " + e.getMessage());
            return false;
        }
    }
}

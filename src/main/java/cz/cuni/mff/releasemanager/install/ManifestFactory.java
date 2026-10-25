package cz.cuni.mff.releasemanager.install;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.verify.SignatureResult;
import cz.cuni.mff.releasemanager.verify.TrustGate;
import cz.cuni.mff.releasemanager.verify.VerificationResult;
import cz.cuni.mff.releasemanager.verify.VerificationStatus;

// turns a completed install into the manifest we keep
public final class ManifestFactory {
    private final PlatformHandler platformHandler;
    private final AppPaths paths;

    public ManifestFactory(PlatformHandler platformHandler, AppPaths paths) {
        this.platformHandler = platformHandler;
        this.paths = paths;
    }

    public AppManifest create(
        RepoRef ref,
        ProviderConfig provider,
        ReleaseAssets release,
        Asset asset,
        Path installed,
        VerificationResult verification,
        SignatureResult signature,
        Instant installedAt,
        List<AppManifest.Backup> backups) {
        Path launcher = platformHandler.launcherPath(ref.provider(), ref.path());
        Path uninstallPath = launcher == null ? installed : launcher;
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            ref.provider(),
            ref.path(),
            release.version(),
            release.releaseId(),
            new AppManifest.Asset(
                asset.name(),
                asset.url(),
                verification.status() == VerificationStatus.VERIFIED
                    ? verification.expected()
                    : null),
            new AppManifest.Install(
                installKind(asset.name()),
                installed.toString(),
                paths.appDir(ref.provider(), ref.path()).toString(),
                new AppManifest.Uninstall(
                    uninstallPath.toString(), List.of(),
                    platformHandler.productCode(installed))),
            new AppManifest.Verification(
                verification.status().name().toLowerCase(Locale.ROOT),
                "sha256",
                verification.checksumAssetName(),
                verification.expected(),
                verification.actual()),
            backups,
            installedAt,
            Instant.now(),
            TrustGate.signatureRecord(signature),
            provider.type(),
            provider.resolvedApi());
    }

    public static String installKind(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".appimage")) {
            return "appimage";
        }
        if (name.endsWith(".msi")) {
            return "msi";
        }
        if (name.endsWith(".exe")) {
            return "exe";
        }
        if (name.endsWith(".dmg")) {
            return "dmg";
        }
        return "unknown";
    }
}

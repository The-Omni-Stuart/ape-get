package cz.cuni.mff.releasemanager.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cz.cuni.mff.releasemanager.json.JsonViews.AppInfo;
import cz.cuni.mff.releasemanager.json.JsonViews.AppView;
import cz.cuni.mff.releasemanager.json.JsonViews.AssetView;
import cz.cuni.mff.releasemanager.json.JsonViews.BackupView;
import cz.cuni.mff.releasemanager.json.JsonViews.SignatureView;
import cz.cuni.mff.releasemanager.json.JsonViews.VerificationView;
import cz.cuni.mff.releasemanager.platform.PackageKind;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;

// json and text views of an installed app
public final class ManifestViews {

    private final PlatformHandler platformHandler;

    public ManifestViews(PlatformHandler platformHandler) {
        this.platformHandler = platformHandler;
    }

    public AppView listEntry(AppManifest manifest) {
        return new AppView(
            manifest.provider(),
            manifest.repo(),
            manifest.version(),
            manifest.installedAt(),
            manifest.updatedAt(),
            manifest.install() == null ? null : manifest.install().path(),
            manifest.asset() == null ? null : manifest.asset().name(),
            manifest.install() == null ? null : manifest.install().kind(),
            manifest.install() == null ? null : manifest.install().command());
    }

    public AppInfo infoEntry(AppManifest manifest) {
        AssetView asset = manifest.asset() == null
            ? new AssetView(null, null, null)
            : new AssetView(
                manifest.asset().name(),
                manifest.asset().url(),
                manifest.asset().sha256());

        AppManifest.Install install = manifest.install();
        AppManifest.Uninstall uninstall = install == null ? null : install.uninstall();
        boolean noCommandPortable = install != null && install.command() == null
            && PackageKind.fromManifest(install.kind()).isPortable()
            && uninstall != null && install.path() != null
            && install.path().equals(uninstall.path());
        String launcher = uninstall == null || noCommandPortable
            ? null : uninstall.path();

        VerificationView verification = manifest.verification() == null
            ? new VerificationView(null, null, null, null, null)
            : new VerificationView(
                manifest.verification().status(),
                manifest.verification().method(),
                manifest.verification().checksumAsset(),
                manifest.verification().expected(),
                manifest.verification().actual());

        SignatureView signature = manifest.signature() == null
            ? new SignatureView(null, null, null, null, null)
            : new SignatureView(
                manifest.signature().status(),
                manifest.signature().mechanism(),
                manifest.signature().signer(),
                manifest.signature().identity(),
                manifest.signature().notarized());

        List<BackupView> backups = new ArrayList<>();
        if (manifest.backups() != null) {
            for (AppManifest.Backup backup : manifest.backups()) {
                backups.add(new BackupView(
                    backup.version(),
                    backup.createdAt(),
                    backup.path(),
                    backup.artifact()));
            }
        }

        return new AppInfo(
            manifest.provider(),
            manifest.repo(),
            manifest.version(),
            manifest.installedAt(),
            manifest.updatedAt(),
            install == null ? null : install.path(),
            platformHandler.installState(manifest)
                .name().toLowerCase(Locale.ROOT),
            asset,
            manifest.releaseId(),
            install == null ? null : install.root(),
            launcher,
            uninstall == null ? null : uninstall.productCode(),
            verification,
            signature,
            backups,
            install == null ? null : install.kind(),
            install == null ? null : install.command(),
            install == null ? null : install.desktopEntry());
    }

    public static String describeVerification(AppManifest.Verification verification) {
        if (verification == null) {
            return "not recorded";
        }
        StringBuilder value = new StringBuilder(verification.status());
        if (verification.method() != null) {
            value.append(" (").append(verification.method());
            if (verification.checksumAsset() != null) {
                value.append(", ").append(verification.checksumAsset());
            }
            value.append(")");
        }
        return value.toString();
    }

    public static String describePinnedSignature(AppManifest.Signature signature) {
        if (signature == null) {
            return "not recorded";
        }
        if (signature.isVerified()) {
            String value = "verified (" + signature.mechanism() + ") by "
                + describeSignature(signature);
            if (signature.notarized() != null) {
                value += signature.notarized() ? ", notarized" : ", not notarized";
            }
            return value;
        }
        return signature.status()
            + (signature.mechanism() == null ? "" : " (" + signature.mechanism() + ")");
    }

    public static String describeSignature(AppManifest.Signature signature) {
        String signer = signature.signer() == null ? "unknown signer" : signature.signer();
        return signature.identity() == null
            ? signer
            : signer + " (" + signature.identity() + ")";
    }
}

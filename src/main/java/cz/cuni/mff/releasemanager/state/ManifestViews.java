package cz.cuni.mff.releasemanager.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;

// json and text views of an installed app
public final class ManifestViews {

    private final PlatformHandler platformHandler;

    public ManifestViews(PlatformHandler platformHandler) {
        this.platformHandler = platformHandler;
    }

    public Map<String, Object> listEntry(AppManifest manifest) {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("provider", manifest.provider());
        app.put("repo", manifest.repo());
        app.put("version", manifest.version());
        app.put("installed_at", manifest.installedAt());
        app.put("updated_at", manifest.updatedAt());
        app.put("install_path",
            manifest.install() == null ? null : manifest.install().path());
        app.put("asset", manifest.asset() == null ? null : manifest.asset().name());
        return app;
    }

    public Map<String, Object> infoEntry(AppManifest manifest) {
        Map<String, Object> info = listEntry(manifest);
        info.put("release_id", manifest.releaseId());
        info.put("install_state", platformHandler.installState(manifest)
            .name().toLowerCase(Locale.ROOT));

        Map<String, Object> asset = new LinkedHashMap<>();
        if (manifest.asset() != null) {
            asset.put("name", manifest.asset().name());
            asset.put("url", manifest.asset().url());
            asset.put("sha256", manifest.asset().sha256());
        }
        info.put("asset", asset);

        if (manifest.install() != null) {
            info.put("install_root", manifest.install().root());
            AppManifest.Uninstall uninstall = manifest.install().uninstall();
            info.put("launcher", uninstall == null ? null : uninstall.path());
            info.put("product_code", uninstall == null ? null : uninstall.productCode());
        }

        Map<String, Object> verification = new LinkedHashMap<>();
        if (manifest.verification() != null) {
            verification.put("status", manifest.verification().status());
            verification.put("method", manifest.verification().method());
            verification.put("checksum_asset", manifest.verification().checksumAsset());
            verification.put("expected", manifest.verification().expected());
            verification.put("actual", manifest.verification().actual());
        }
        info.put("verification", verification);

        Map<String, Object> signature = new LinkedHashMap<>();
        if (manifest.signature() != null) {
            signature.put("status", manifest.signature().status());
            signature.put("mechanism", manifest.signature().mechanism());
            signature.put("signer", manifest.signature().signer());
            signature.put("identity", manifest.signature().identity());
            signature.put("notarized", manifest.signature().notarized());
        }
        info.put("signature", signature);

        List<Map<String, Object>> backups = new ArrayList<>();
        if (manifest.backups() != null) {
            for (AppManifest.Backup backup : manifest.backups()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("version", backup.version());
                entry.put("created_at", backup.createdAt());
                entry.put("path", backup.path());
                entry.put("artifact", backup.artifact());
                backups.add(entry);
            }
        }
        info.put("backups", backups);
        return info;
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

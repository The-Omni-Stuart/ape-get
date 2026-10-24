package cz.cuni.mff.releasemanager.state;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-application manifest and source of truth for an installed release.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppManifest(
    String kind,
    @JsonProperty("schema_version") int schemaVersion,
    String provider,
    String repo,
    String version,
    @JsonProperty("release_id") Long releaseId,
    Asset asset,
    Install install,
    Verification verification,
    List<Backup> backups,
    @JsonProperty("installed_at") Instant installedAt,
    @JsonProperty("updated_at") Instant updatedAt,
    Signature signature
) {
    public static final String KIND = "grm.manifest";
    public static final int SCHEMA_VERSION = 1;

    public AppManifest(
        String kind,
        int schemaVersion,
        String provider,
        String repo,
        String version,
        Long releaseId,
        Asset asset,
        Install install,
        Verification verification,
        List<Backup> backups,
        Instant installedAt,
        Instant updatedAt
    ) {
        this(kind, schemaVersion, provider, repo, version, releaseId, asset, install,
            verification, backups, installedAt, updatedAt, null);
    }

    @JsonIgnore
    public boolean isValid() {
        try {
            AppPaths.repoDirectoryName(repo);
        } catch (RuntimeException e) {
            return false;
        }
        return KIND.equals(kind)
            && schemaVersion == SCHEMA_VERSION
            && provider != null && !provider.isBlank()
            && repo != null && !repo.isBlank()
            && asset != null
            && asset.name() != null && !asset.name().isBlank()
            && asset.url() != null && !asset.url().isBlank()
            && install != null
            && install.path() != null && !install.path().isBlank()
            && installedAt != null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Asset(String name, String url, Long size, String sha256) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Install(String kind, String path, String root, Uninstall uninstall) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Uninstall(
        String path,
        List<String> args,
        @JsonProperty("product_code") String productCode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Verification(
        String status,
        String method,
        @JsonProperty("checksum_asset") String checksumAsset,
        String expected,
        String actual
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Signature(
        String status,
        String mechanism,
        String signer,
        String identity,
        Boolean notarized
    ) {
        public Signature(
            String status, String mechanism, String signer, String identity) {
            this(status, mechanism, signer, identity, null);
        }

        @JsonIgnore
        public boolean isVerified() {
            return "verified".equals(status) && identity != null && !identity.isBlank();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Backup(
        String version,
        String path,
        String artifact,
        @JsonProperty("install_path") String installPath,
        @JsonProperty("created_at") Instant createdAt,
        String kind,
        String manifest
    ) {}
}

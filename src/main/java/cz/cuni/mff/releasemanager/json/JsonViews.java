package cz.cuni.mff.releasemanager.json;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

// typed shapes for every JSON response; Jackson serialises the records
public final class JsonViews {

    private JsonViews() {}

    public record AppView(
        String provider,
        String repo,
        String version,
        @JsonProperty("installed_at") Instant installedAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("install_path") String installPath,
        String asset,
        String kind,
        String command) {}

    public record AppList(List<AppView> apps) {}

    public record AdoptedView(AppView adopted) {}

    public record AssetView(String name, String url, String sha256) {}

    public record VerificationView(
        String status,
        String method,
        @JsonProperty("checksum_asset") String checksumAsset,
        String expected,
        String actual) {}

    public record SignatureView(
        String status,
        String mechanism,
        String signer,
        String identity,
        Boolean notarized) {}

    public record BackupView(
        String version,
        @JsonProperty("created_at") Instant createdAt,
        String path,
        String artifact) {}

    public record AppInfo(
        String provider,
        String repo,
        String version,
        @JsonProperty("installed_at") Instant installedAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("install_path") String installPath,
        @JsonProperty("install_state") String installState,
        AssetView asset,
        @JsonProperty("release_id") Long releaseId,
        @JsonProperty("install_root") String installRoot,
        String launcher,
        @JsonProperty("product_code") String productCode,
        VerificationView verification,
        SignatureView signature,
        List<BackupView> backups,
        String kind,
        String command,
        @JsonProperty("desktop_entry") String desktopEntry) {}

    public record InstallPlan(
        @JsonProperty("dry_run") boolean dryRun,
        String action,
        String provider,
        String repo,
        String version,
        String asset,
        String source,
        String launcher,
        String kind,
        String command) {}

    public record ReleaseResult(
        String provider,
        String repo,
        String version,
        String asset,
        String path,
        String launcher,
        String kind,
        String command) {}

    public record InstalledResult(ReleaseResult installed) {}

    public record UpdatedResult(ReleaseResult updated) {}

    public record UpToDateView(String provider, String repo, String version) {}

    public record UpToDateResult(
        @JsonProperty("up_to_date") UpToDateView upToDate) {}

    public record UpdateEntry(
        String provider,
        String repo,
        String from,
        String to,
        String status) {}

    public record UpdateBatch(List<UpdateEntry> updates) {}

    public record OutdatedEntry(
        String provider,
        String repo,
        String current,
        String latest,
        String asset) {}

    public record OutdatedList(List<OutdatedEntry> outdated) {}

    public record SearchEntry(String provider, String repo, String description) {}

    public record SearchResults(List<SearchEntry> results) {}

    public record SearchError(String provider, String error) {}

    public record SearchReport(
        List<SearchEntry> results, List<SearchError> errors) {}

    public record ProviderConfigView(
        String name,
        String type,
        String url,
        String api,
        @JsonProperty("token_env") String tokenEnv,
        @JsonProperty("built_in") boolean builtIn) {}

    public record ProviderEntry(
        String name,
        String type,
        String url,
        String api,
        @JsonProperty("token_env") String tokenEnv,
        @JsonProperty("token_state") String tokenState,
        @JsonProperty("built_in") boolean builtIn,
        long packages) {}

    public record ProviderList(List<ProviderEntry> providers) {}

    public record PackageView(String repo, String version) {}

    public record ProviderDetail(
        String name,
        String type,
        String url,
        String api,
        @JsonProperty("token_env") String tokenEnv,
        @JsonProperty("built_in") boolean builtIn,
        List<PackageView> packages) {}

    public record ProviderResult(ProviderConfigView provider) {}

    public record RemovedProvider(String removed) {}

    public record UninstallPlan(
        @JsonProperty("dry_run") boolean dryRun,
        String action,
        String provider,
        String repo,
        String path) {}

    public record UninstallResult(
        String provider, String repo, String path) {}

    public record UninstalledResult(
        @JsonProperty("uninstalled") UninstallResult uninstalled) {}

    public record RollbackPlan(
        @JsonProperty("dry_run") boolean dryRun,
        String action,
        String provider,
        String repo,
        String from,
        String to,
        String backup) {}

    public record RollbackResult(
        String provider, String repo, String version) {}

    public record RolledBack(
        @JsonProperty("rolled_back") RollbackResult rolledBack) {}

    public record ToolView(String name, boolean required, boolean available) {}

    public record PinView(String repo, String signer, String identity) {}

    public record DoctorReport(
        boolean healthy,
        @JsonProperty("state_root") String stateRoot,
        String config,
        String index,
        int manifests,
        List<String> issues,
        List<String> warnings,
        @JsonProperty("signature_tools") List<ToolView> tools,
        List<PinView> pins,
        @JsonProperty("free_bytes") long freeBytes) {}
}

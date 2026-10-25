package cz.cuni.mff.releasemanager.state;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.UpdateChannel;

/**
 * Persistent tool configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GrmConfig(
    String kind,
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("strict_verification") boolean strictVerification,
    @JsonProperty("backup_count") int backupCount,
    @JsonProperty("install_root") String installRoot,
    @JsonProperty("require_signature") boolean requireSignature,
    Map<String, ProviderConfig> providers,
    @JsonProperty("search_all_providers") Boolean searchAllProviders,
    @JsonProperty("update_channel") String updateChannel,
    @JsonProperty("auto_update") String autoUpdate,
    @JsonProperty("update_interval_hours") int updateIntervalHours
) {
    public static final String KIND = "grm.config";
    public static final int SCHEMA_VERSION = 1;

    public GrmConfig {
        updateChannel = UpdateChannel.from(updateChannel).id();
        autoUpdate = AutoUpdateMode.from(autoUpdate).id();
        if (updateIntervalHours <= 0) {
            updateIntervalHours = 24;
        }
    }

    public GrmConfig(
        String kind,
        int schemaVersion,
        boolean strictVerification,
        int backupCount,
        String installRoot
    ) {
        this(kind, schemaVersion, strictVerification, backupCount, installRoot,
            false, Map.of(), true, "stable", "off", 24);
    }

    public GrmConfig(
        String kind,
        int schemaVersion,
        boolean strictVerification,
        int backupCount,
        String installRoot,
        boolean requireSignature
    ) {
        this(kind, schemaVersion, strictVerification, backupCount, installRoot,
            requireSignature, Map.of(), true, "stable", "off", 24);
    }

    public GrmConfig(
        String kind,
        int schemaVersion,
        boolean strictVerification,
        int backupCount,
        String installRoot,
        boolean requireSignature,
        Map<String, ProviderConfig> providers
    ) {
        this(kind, schemaVersion, strictVerification, backupCount, installRoot,
            requireSignature, providers, true, "stable", "off", 24);
    }

    // old eight-field form, kept for callers and tests
    public GrmConfig(
        String kind,
        int schemaVersion,
        boolean strictVerification,
        int backupCount,
        String installRoot,
        boolean requireSignature,
        Map<String, ProviderConfig> providers,
        Boolean searchAllProviders
    ) {
        this(kind, schemaVersion, strictVerification, backupCount, installRoot,
            requireSignature, providers, searchAllProviders, "stable", "off", 24);
    }

    public static GrmConfig defaults() {
        return new GrmConfig(KIND, SCHEMA_VERSION, false, 1, null, false,
            Map.of(), true, "stable", "off", 24);
    }

    public GrmConfig withProviders(Map<String, ProviderConfig> updated) {
        return new GrmConfig(
            kind, schemaVersion, strictVerification, backupCount, installRoot,
            requireSignature, updated, searchAllProviders,
            updateChannel, autoUpdate, updateIntervalHours);
    }

    @JsonIgnore
    public boolean searchAllEnabled() {
        return !Boolean.FALSE.equals(searchAllProviders);
    }

    @JsonIgnore
    public UpdateChannel channel() {
        return UpdateChannel.from(updateChannel);
    }

    @JsonIgnore
    public AutoUpdateMode autoUpdateMode() {
        return AutoUpdateMode.from(autoUpdate);
    }

    @JsonIgnore
    public boolean isValid() {
        return KIND.equals(kind)
            && schemaVersion == SCHEMA_VERSION
            && backupCount >= 0;
    }
}

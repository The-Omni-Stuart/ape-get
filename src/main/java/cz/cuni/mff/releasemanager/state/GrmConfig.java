package cz.cuni.mff.releasemanager.state;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import cz.cuni.mff.releasemanager.provider.ProviderConfig;

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
    Map<String, ProviderConfig> providers
) {
    public static final String KIND = "grm.config";
    public static final int SCHEMA_VERSION = 1;

    public GrmConfig(
        String kind,
        int schemaVersion,
        boolean strictVerification,
        int backupCount,
        String installRoot
    ) {
        this(kind, schemaVersion, strictVerification, backupCount, installRoot, false, Map.of());
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
            requireSignature, Map.of());
    }

    public static GrmConfig defaults() {
        return new GrmConfig(KIND, SCHEMA_VERSION, false, 1, null, false, Map.of());
    }

    @JsonIgnore
    public boolean isValid() {
        return KIND.equals(kind)
            && schemaVersion == SCHEMA_VERSION
            && backupCount >= 0;
    }
}

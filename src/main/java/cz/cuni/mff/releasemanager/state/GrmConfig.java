package cz.cuni.mff.releasemanager.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Persistent tool configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GrmConfig(
    String kind,
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("strict_verification") boolean strictVerification,
    @JsonProperty("backup_count") int backupCount,
    @JsonProperty("install_root") String installRoot
) {
    public static final String KIND = "grm.config";
    public static final int SCHEMA_VERSION = 1;

    public static GrmConfig defaults() {
        return new GrmConfig(KIND, SCHEMA_VERSION, false, 1, null);
    }

    @JsonIgnore
    public boolean isValid() {
        return KIND.equals(kind)
            && schemaVersion == SCHEMA_VERSION
            && backupCount >= 0;
    }
}

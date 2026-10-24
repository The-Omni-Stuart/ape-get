package cz.cuni.mff.releasemanager.state;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rebuildable index of installed applications. The index carries a full
 * manifest snapshot so a lost manifest can be restored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GrmIndex(
    String kind,
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("generated_at") Instant generatedAt,
    List<Entry> apps
) {
    public static final String KIND = "grm.index";
    public static final int SCHEMA_VERSION = 1;

    public static GrmIndex empty() {
        return new GrmIndex(KIND, SCHEMA_VERSION, Instant.now(), List.of());
    }

    @JsonIgnore
    public boolean isValid() {
        return KIND.equals(kind)
            && schemaVersion == SCHEMA_VERSION
            && generatedAt != null
            && apps != null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
        String repo,
        String version,
        @JsonProperty("installed_at") Instant installedAt,
        @JsonProperty("install_path") String installPath,
        String manifest,
        AppManifest snapshot
    ) {}
}

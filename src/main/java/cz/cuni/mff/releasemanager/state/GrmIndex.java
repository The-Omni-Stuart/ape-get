package cz.cuni.mff.releasemanager.state;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// rebuildable index; each entry carries a manifest snapshot for recovery
@JsonIgnoreProperties(ignoreUnknown = true)
public record GrmIndex(
    String kind,
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("generated_at") Instant generatedAt,
    List<Entry> apps
) {
    public static final String KIND = "grm.index";
    public static final int SCHEMA_VERSION = 1;

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
        @JsonProperty("install_path") String installPath,
        AppManifest snapshot,
        String provider
    ) {
        public Entry(String repo, String installPath, AppManifest snapshot) {
            this(repo, installPath, snapshot,
                snapshot == null ? null : snapshot.provider());
        }
    }
}

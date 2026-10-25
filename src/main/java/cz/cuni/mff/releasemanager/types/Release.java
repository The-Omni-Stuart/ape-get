package cz.cuni.mff.releasemanager.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// GitHub release response: tag, id, and assets
@JsonIgnoreProperties(ignoreUnknown = true)
public record Release(
    @JsonProperty("tag_name") String tagName,
    @JsonProperty("id") Long id,
    List<Asset> assets
) {}
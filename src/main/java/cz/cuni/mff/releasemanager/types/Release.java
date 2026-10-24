package cz.cuni.mff.releasemanager.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a Github release.
 * This record class is used to deserialize the JSON response from the Github API for releases.
 * @param url The URL of the release.
 * @param name The name of the release.
 * @param tagName The release tag.
 * @param id The GitHub release id.
 * @param assets The list of assets associated with the release.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Release(
    String url,
    String name,
    @JsonProperty("tag_name") String tagName,
    @JsonProperty("id") Long id,
    List<Asset> assets
) {}
package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Path;

/**
 * Describes one install operation.
 */
public record InstallRequest(
    Path asset,
    String provider,
    String repoFullName,
    String version
) {}

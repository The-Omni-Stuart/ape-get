package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Path;

/**
 * Describes one install operation.
 */
public record InstallRequest(
    Path asset,
    String provider,
    String repoFullName,
    String version,
    PackageKind kind,
    String command,
    boolean assimilate,
    boolean desktop,
    String icon,
    boolean terminal
) {
    // old installer paths do not need a portable kind or command
    public InstallRequest(Path asset, String provider, String repoFullName, String version) {
        this(asset, provider, repoFullName, version,
            PackageKind.UNKNOWN, null, false, false, null, false);
    }

    public InstallRequest(
        Path asset,
        String provider,
        String repoFullName,
        String version,
        PackageKind kind,
        String command,
        boolean assimilate
    ) {
        this(asset, provider, repoFullName, version, kind, command, assimilate,
            false, null, false);
    }
}

package cz.cuni.mff.releasemanager.install;

import java.nio.file.Path;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.platform.PackageKind;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.CommandNames;
import cz.cuni.mff.releasemanager.utils.Output;

// turns CLI choices into a concrete portable package plan
public final class PackagePlanner {
    private final Platform.OS os;
    private final CmdParser cmdParser;

    public PackagePlanner(Platform.OS os, CmdParser cmdParser) {
        this.os = os;
        this.cmdParser = cmdParser;
    }

    // throws for an invalid --kind value
    public PackageKind requestedKind() {
        return PackageKind.fromOption(cmdParser.getKind());
    }

    public List<Asset> candidates(
        List<Asset> installable, PackageKind requested) {
        return candidates(installable, requested, false);
    }

    // an explicit --asset may name an extensionless APE
    public List<Asset> candidates(
        List<Asset> installable, PackageKind requested, boolean allowUnknown) {
        if (requested == null) {
            return installable;
        }
        return installable.stream()
            .filter(asset -> matches(requested, asset, allowUnknown))
            .toList();
    }

    public PackagePlan plan(
        RepoRef ref, Asset asset, PackageKind requested, AppManifest previous) {
        PackageKind kind = tentative(asset, requested, previous);
        if (kind == PackageKind.UNKNOWN) {
            Output.error("Cannot determine how to install " + asset.name()
                + "; use --kind.");
            return null;
        }
        if (kind.isPortable() && !kind.supportsOn(os)) {
            Output.error("Package kind " + kind.manifestName()
                + " is not supported on " + os + ".");
            return null;
        }
        String command = command(kind, ref, asset, previous);
        if (command == null && !cmdParser.isNoCommand() && kind.isPortable()) {
            return null;
        }
        if (cmdParser.isAssimilate() && kind != PackageKind.APE) {
            Output.error("--assimilate is only supported for APE packages.");
            return null;
        }
        if (cmdParser.isDesktop()) {
            if (!kind.isPortable() || command == null) {
                Output.error("--desktop requires a portable package with a command.");
                return null;
            }
            if (os != Platform.OS.LINUX) {
                Output.error("Desktop entries are currently supported on Linux only.");
                return null;
            }
        }
        return new PackagePlan(kind, asset, command);
    }

    public PackageKind confirmAfterDownload(PackagePlan plan, Path downloaded) {
        if (PackageKind.hasApeMagic(downloaded)) {
            return PackageKind.APE;
        }
        if (plan.kind() == PackageKind.APE) {
            Output.error("Asset is not an APE: " + plan.asset().name());
            return null;
        }
        return plan.kind();
    }

    private PackageKind tentative(
        Asset asset, PackageKind requested, AppManifest previous) {
        if (requested != null) {
            return requested;
        }
        if (previous != null && previous.install() != null) {
            PackageKind previousKind =
                PackageKind.fromManifest(previous.install().kind());
            if (previousKind != PackageKind.UNKNOWN) {
                return previousKind;
            }
        }
        return PackageKind.fromAssetName(asset.name());
    }

    private String command(
        PackageKind kind, RepoRef ref, Asset asset, AppManifest previous) {
        if (!kind.isPortable() || cmdParser.isNoCommand()) {
            return null;
        }
        if (previous != null && previous.install() != null
            && previous.install().command() != null) {
            return previous.install().command();
        }
        String requested = cmdParser.getCommandName();
        if (requested != null && !requested.isBlank()) {
            try {
                return CommandNames.validate(requested);
            } catch (IllegalArgumentException e) {
                Output.error(e.getMessage());
                return null;
            }
        }
        try {
            return CommandNames.fromAsset(ref, asset);
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return null;
        }
    }

    private static boolean matches(
        PackageKind requested, Asset asset, boolean allowUnknown) {
        PackageKind actual = PackageKind.fromAssetName(asset.name());
        if (allowUnknown && actual == PackageKind.UNKNOWN) {
            return true;
        }
        return switch (requested) {
            case APPIMAGE, MSI, DMG -> actual == requested;
            case PORTABLE_EXE, NSIS -> actual == PackageKind.NSIS;
            case APE -> actual == PackageKind.APE || actual == PackageKind.NSIS;
            case UNKNOWN -> true;
        };
    }
}

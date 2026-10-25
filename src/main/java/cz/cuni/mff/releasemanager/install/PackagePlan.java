package cz.cuni.mff.releasemanager.install;

import cz.cuni.mff.releasemanager.platform.PackageKind;
import cz.cuni.mff.releasemanager.types.Asset;

// what will be installed and what command it will expose
public record PackagePlan(
    PackageKind kind,
    Asset asset,
    String command
) {}

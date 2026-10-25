package cz.cuni.mff.releasemanager.install;

import java.nio.file.Path;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.json.JsonViews.InstallPlan;
import cz.cuni.mff.releasemanager.json.JsonViews.InstalledResult;
import cz.cuni.mff.releasemanager.json.JsonViews.ReleaseResult;
import cz.cuni.mff.releasemanager.json.JsonViews.UpToDateResult;
import cz.cuni.mff.releasemanager.json.JsonViews.UpToDateView;
import cz.cuni.mff.releasemanager.json.JsonViews.UpdatedResult;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;

// prints the dry-run plan, the JSON result, and the success line
public final class InstallReporter {
    private final CmdParser cmdParser;
    private final PlatformHandler platformHandler;

    public InstallReporter(
        CmdParser cmdParser,
        PlatformHandler platformHandler) {
        this.cmdParser = cmdParser;
        this.platformHandler = platformHandler;
    }

    public void printPlan(
        String action, RepoRef ref, ReleaseAssets release, PackagePlan plan) {
        Asset asset = plan.asset();
        Path launcher;
        if (plan.kind().isPortable()) {
            launcher = plan.command() == null
                ? null
                : platformHandler.launcherPath(
                    ref.provider(), ref.path(), plan.command());
        } else {
            launcher = platformHandler.launcherPath(ref.provider(), ref.path());
        }
        if (cmdParser.isJson()) {
            Output.json(new InstallPlan(
                true,
                action,
                ref.provider(),
                ref.path(),
                release.version(),
                asset.name(),
                asset.url(),
                launcher == null ? null : launcher.toString(),
                plan.kind().manifestName(),
                plan.command()));
            return;
        }
        Output.data("Dry run: would " + action + " " + ref.display()
            + (action.equals("install") ? " " : " to ") + versionLabel(release) + ".");
        Output.data("  Asset:  " + asset.name());
        Output.data("  Source: " + asset.url());
        if (launcher != null) {
            Output.data("  Launcher: " + launcher);
        }
        Output.data("No changes were made.");
    }

    public void installed(
        RepoRef ref, ReleaseAssets release, PackagePlan plan, Path installed) {
        if (cmdParser.isJson()) {
            Output.json(new InstalledResult(result(ref, release, plan, installed)));
        } else {
            Output.info("Installation successful.");
        }
    }

    public void updated(
        RepoRef ref, ReleaseAssets release, PackagePlan plan, Path installed) {
        if (cmdParser.isJson()) {
            Output.json(new UpdatedResult(result(ref, release, plan, installed)));
        } else {
            Output.info("Successfully updated.");
        }
    }

    public void upToDate(RepoRef ref, String version) {
        if (cmdParser.isJson()) {
            Output.json(new UpToDateResult(
                new UpToDateView(ref.provider(), ref.path(), version)));
        } else {
            Output.info("Already up to date.");
        }
    }

    private ReleaseResult result(
        RepoRef ref, ReleaseAssets release, PackagePlan plan, Path installed) {
        Path launcher;
        if (plan.kind().isPortable()) {
            launcher = plan.command() == null
                ? null
                : platformHandler.launcherPath(
                    ref.provider(), ref.path(), plan.command());
        } else {
            launcher = platformHandler.launcherPath(ref.provider(), ref.path());
        }
        return new ReleaseResult(
            ref.provider(),
            ref.path(),
            release.version(),
            plan.asset().name(),
            installed == null ? null : installed.toString(),
            launcher == null ? null : launcher.toString(),
            plan.kind().manifestName(),
            plan.command());
    }

    private static String versionLabel(ReleaseAssets release) {
        return release.version() == null ? "(unknown version)" : release.version();
    }
}

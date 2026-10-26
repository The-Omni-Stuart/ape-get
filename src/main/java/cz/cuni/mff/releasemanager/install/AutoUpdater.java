package cz.cuni.mff.releasemanager.install;

import java.io.IOException;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AutoUpdateMode;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.state.UpdateLog;
import cz.cuni.mff.releasemanager.types.UpdateChannel;
import cz.cuni.mff.releasemanager.utils.Output;

// runs the configured automatic check or update, at most once per interval
public final class AutoUpdater {
    private final GrmConfig config;
    private final StateStore stateStore;
    private final CmdParser cmdParser;
    private final UpdateChecker checker;
    private final InstallService installService;
    private final UpdateLog log;

    public AutoUpdater(
        GrmConfig config,
        StateStore stateStore,
        CmdParser cmdParser,
        UpdateChecker checker,
        InstallService installService,
        UpdateLog log) {
        this.config = config;
        this.stateStore = stateStore;
        this.cmdParser = cmdParser;
        this.checker = checker;
        this.installService = installService;
        this.log = log;
    }

    public void maybeRun() {
        AutoUpdateMode mode = config.autoUpdateMode();
        if (!mode.enabled() || !log.due(config.updateIntervalHours())) {
            return;
        }
        UpdateChannel channel = channel();
        if (mode == AutoUpdateMode.CHECK) {
            check(channel);
        } else {
            apply(channel);
        }
    }

    private void check(UpdateChannel channel) {
        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            log.append("check failed: " + e.getMessage());
            return;
        }
        UpdateChecker.Report report = checker.check(manifests, channel);
        if (report.outdated().isEmpty()) {
            log.append("check channel=" + channel.id() + ": up to date");
            return;
        }
        log.append("check channel=" + channel.id() + ": "
            + report.outdated().size() + " update(s) available");
        Output.info(report.outdated().size() + " update(s) available; run 'update --all'.");
    }

    private void apply(UpdateChannel channel) {
        InstallService.Batch batch = installService.updateAllBatch(false, false);
        log.append("apply channel=" + channel.id() + ": "
            + (batch.ok() ? "completed" : "completed with errors"));
        for (UpdateResult result : batch.results()) {
            if (result.status() == UpdateResult.Status.FAILED) {
                log.append("apply failed " + result.provider() + ":" + result.repo()
                    + ": " + (result.message() == null || result.message().isBlank()
                        ? "update failed" : result.message()));
            }
        }
        Output.info(batch.ok()
            ? "Automatic update completed."
            : "Automatic update completed with errors; see the update log.");
    }

    private UpdateChannel channel() {
        String value = cmdParser.getChannel();
        try {
            return value == null || value.isBlank()
                ? config.channel() : UpdateChannel.parse(value);
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return config.channel();
        }
    }
}

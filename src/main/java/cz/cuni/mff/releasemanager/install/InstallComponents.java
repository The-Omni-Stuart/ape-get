package cz.cuni.mff.releasemanager.install;

// the collaborators one install or update needs, built per command
public record InstallComponents(
    ReleaseFetcher fetcher,
    AssetChooser chooser,
    ReleaseDownloader downloader,
    ManifestFactory manifestFactory,
    InstallReporter reporter,
    TrustGateFactory trust,
    BackupService backups,
    PlatformInstaller platform) {}

package cz.cuni.mff.releasemanager.commands;

import cz.cuni.mff.releasemanager.json.JsonViews.ProviderConfigView;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.utils.Output;

// shared provider output
final class ProviderViews {

    private ProviderViews() {}

    static ProviderConfigView entry(ProviderConfig config) {
        return new ProviderConfigView(
            config.name(),
            config.type(),
            config.url(),
            config.resolvedApi(),
            config.tokenEnv(),
            ProviderRegistry.builtIns().containsKey(config.name()));
    }

    static boolean usage() {
        Output.error("Usage:");
        Output.error("  providers list");
        Output.error("  providers show <name>");
        Output.error("  providers add <name> <url> [--type auto|github|gitea|gitlab|rngit]"
            + " [--api URL] [--token-env VAR]");
        Output.error("  providers update <name> [--type ...] [--api URL] [--url URL]"
            + " [--token-env VAR] [--force]");
        Output.error("  providers remove <name> [--abandon | --purge]");
        Output.error("  providers recover <name> [--type ...] [--api URL]");
        return false;
    }
}

package cz.cuni.mff.releasemanager.install;

import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.Output;

// picks an asset by --asset, by the previous name, or by prompting
public final class AssetChooser {
    private final CmdParser cmdParser;

    public AssetChooser(CmdParser cmdParser) {
        this.cmdParser = cmdParser;
    }

    public Asset choose(List<Asset> assets) {
        String requested = cmdParser.getAsset();
        if (requested == null || requested.isBlank()) {
            return single(assets);
        }
        String value = requested.trim();
        if (value.matches("\\d+")) {
            try {
                int index = Integer.parseInt(value);
                if (index >= 1 && index <= assets.size()) {
                    return assets.get(index - 1);
                }
            } catch (NumberFormatException ignored) {
                // fall through to the name match
            }
        }
        for (Asset asset : assets) {
            if (asset.name().equalsIgnoreCase(value)) {
                return asset;
            }
        }
        Output.error("No installable asset named '" + value + "'. Available assets:");
        for (int i = 0; i < assets.size(); i++) {
            Output.error("  " + (i + 1) + ": " + assets.get(i).name());
        }
        return null;
    }

    public Asset chooseForUpdate(List<Asset> assets, AppManifest previous) {
        String requested = cmdParser.getAsset();
        if (requested != null && !requested.isBlank()) {
            return choose(assets);
        }
        for (Asset asset : assets) {
            if (asset.name().equals(previous.asset().name())) {
                return asset;
            }
        }
        return single(assets);
    }

    private Asset single(List<Asset> assets) {
        if (assets.size() == 1) {
            return assets.get(0);
        }
        if (cmdParser.isJson() || System.console() == null) {
            Output.error("Multiple assets found; choose one with --asset:");
            for (int i = 0; i < assets.size(); i++) {
                Output.error("  " + (i + 1) + ": " + assets.get(i).name());
            }
            return null;
        }
        Output.error("Multiple assets found. Please select one:");
        for (int i = 0; i < assets.size(); i++) {
            Output.error((i + 1) + ": " + assets.get(i).name());
        }
        int choice = choice(1, assets.size());
        return choice < 1 ? null : assets.get(choice - 1);
    }

    private int choice(int min, int max) {
        while (true) {
            int input = cmdParser.getUserInput();
            if (input < 0) {
                return -1;
            }
            if (input >= min && input <= max) {
                return input;
            }
            Output.error("Invalid choice. Valid options are: " + min + " - " + max);
        }
    }
}

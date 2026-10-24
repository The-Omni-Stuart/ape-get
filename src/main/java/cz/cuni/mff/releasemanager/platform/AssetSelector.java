package cz.cuni.mff.releasemanager.platform;

import java.util.List;
import java.util.regex.Pattern;

import cz.cuni.mff.releasemanager.types.Asset;

/**
 * Prefers release assets built for the current machine architecture.
 */
public final class AssetSelector {

    private static final Pattern UNIVERSAL = Pattern.compile(
        "(?i)(?<![a-z0-9])(universal2?|all-arch)(?![a-z0-9])");
    private static final Pattern ARM_64 = Pattern.compile(
        "(?i)(?<![a-z0-9])(aarch64|arm64|armv8|arm[-_]64)(?![a-z0-9])");
    private static final Pattern X86_64 = Pattern.compile(
        "(?i)(?<![a-z0-9])(x86[-_]64|amd64|x64|win64)(?![a-z0-9])");
    private static final Pattern ARM_32 = Pattern.compile(
        "(?i)(?<![a-z0-9])(armv[67]l?|armhf|arm[-_]32)(?![a-z0-9])");
    private static final Pattern X86_32 = Pattern.compile(
        "(?i)(?<![a-z0-9])(x86|i[3-6]86|win32|ia32)(?![a-z0-9])");

    private AssetSelector() {}

    /**
     * Keeps assets that explicitly match the current architecture, plus universal and
     * untagged assets. Falls back to the original list when nothing matches, so a
     * foreign-only release can still be installed deliberately.
     * @param assets installable assets
     * @param architecture current machine architecture
     * @return filtered assets, or the original list when no native asset exists
     */
    public static List<Asset> preferArchitecture(
        List<Asset> assets, Platform.Architecture architecture) {
        if (assets == null || assets.size() < 2 || architecture == null) {
            return assets == null ? List.of() : assets;
        }
        boolean tagged = assets.stream().anyMatch(asset -> detect(asset.name()) != null);
        if (!tagged) {
            return assets;
        }

        List<Asset> matching = assets.stream()
            .filter(asset -> {
                Platform.Architecture detected = detect(asset.name());
                return detected == null
                    || detected == Platform.Architecture.UNIVERSAL
                    || detected == architecture;
            })
            .toList();
        return matching.isEmpty() ? assets : matching;
    }

    static Platform.Architecture detect(String name) {
        if (name == null) {
            return null;
        }
        if (UNIVERSAL.matcher(name).find()) {
            return Platform.Architecture.UNIVERSAL;
        }
        if (ARM_64.matcher(name).find()) {
            return Platform.Architecture.ARM_64;
        }
        if (X86_64.matcher(name).find()) {
            return Platform.Architecture.X86_64;
        }
        if (ARM_32.matcher(name).find()) {
            return Platform.Architecture.ARM_32;
        }
        if (X86_32.matcher(name).find()) {
            return Platform.Architecture.X86_32;
        }
        return null;
    }
}

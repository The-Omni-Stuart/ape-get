package cz.cuni.mff.releasemanager.provider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.utils.ChildEnvironment;
import cz.cuni.mff.releasemanager.utils.Output;

/**
 * Built-in provider instances plus user-defined ones from config.
 */
public final class ProviderRegistry {

    private static final Map<String, ProviderConfig> BUILT_INS = Map.of(
        "github", new ProviderConfig("github", "github",
            "https://github.com", "https://api.github.com", null),
        "codeberg", new ProviderConfig("codeberg", "gitea",
            "https://codeberg.org", "https://codeberg.org/api/v1", null),
        "gitlab", new ProviderConfig("gitlab", "gitlab",
            "https://gitlab.com", "https://gitlab.com/api/v4", null));

    private final Map<String, ProviderConfig> providers;

    public ProviderRegistry() {
        this(Map.of());
    }

    public ProviderRegistry(Map<String, ProviderConfig> configured) {
        Map<String, ProviderConfig> merged = new LinkedHashMap<>(BUILT_INS);
        if (configured != null) {
            for (Map.Entry<String, ProviderConfig> entry : configured.entrySet()) {
                String name = entry.getKey();
                ProviderConfig config = entry.getValue();
                if (BUILT_INS.containsKey(name)) {
                    Output.error("Warning: ignoring provider config for built-in " + name + ".");
                    continue;
                }
                if (!ProviderConfig.validName(name) || config == null || !config.isValid()) {
                    Output.error("Warning: ignoring invalid provider config for " + name + ".");
                    continue;
                }
                merged.put(name.toLowerCase(Locale.ROOT), config.named(name));
            }
        }
        this.providers = Map.copyOf(merged);
        for (ProviderConfig config : this.providers.values()) {
            ChildEnvironment.register(config.tokenEnv());
        }
    }

    public static Map<String, ProviderConfig> builtIns() {
        return BUILT_INS;
    }

    public Optional<ProviderConfig> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(name.toLowerCase(Locale.ROOT)));
    }

    public Collection<ProviderConfig> all() {
        return List.copyOf(providers.values());
    }
}

package cz.cuni.mff.releasemanager.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;

import cz.cuni.mff.releasemanager.utils.Output;

/**
 * Loads and creates grm_config.json.
 */
public final class ConfigStore {
    /**
     * @param config loaded configuration
     * @param created true if defaults were created on first run
     * @param quarantined path to a corrupt file that was moved aside, or null
     */
    public record LoadResult(GrmConfig config, boolean created, Path quarantined) {}

    private final AppPaths paths;

    public ConfigStore(AppPaths paths) {
        this.paths = paths;
    }

    /**
     * Loads configuration, creating defaults when missing or unreadable.
     * @return load result
     * @throws IOException if the state directory cannot be written
     */
    public LoadResult load() throws IOException {
        Path file = paths.configFile();
        if (!Files.exists(file)) {
            GrmConfig defaults = GrmConfig.defaults();
            StateJson.write(file, defaults);
            return new LoadResult(defaults, true, null);
        }

        try {
            GrmConfig config = StateJson.MAPPER.readValue(file.toFile(), GrmConfig.class);
            if (config != null && config.isValid()) {
                return new LoadResult(config, false, null);
            }
        } catch (JsonProcessingException e) {
            // fall through to quarantine
        }

        Path quarantined = quarantine(file);
        GrmConfig defaults = GrmConfig.defaults();
        StateJson.write(file, defaults);
        return new LoadResult(defaults, false, quarantined);
    }

    /**
     * Writes configuration atomically.
     * @param config configuration to persist
     * @throws IOException if the file cannot be written
     */
    public void save(GrmConfig config) throws IOException {
        if (config == null || !config.isValid()) {
            throw new IllegalArgumentException("Invalid configuration");
        }
        StateJson.write(paths.configFile(), config);
    }

    private Path quarantine(Path file) throws IOException {
        Path quarantined = file.resolveSibling(
            file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
        Files.move(file, quarantined, StandardCopyOption.REPLACE_EXISTING);
        Output.error("Warning: corrupt config moved to " + quarantined);
        return quarantined;
    }
}

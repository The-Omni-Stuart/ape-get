package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import cz.cuni.mff.releasemanager.types.ReleaseInfo;
import cz.cuni.mff.releasemanager.types.ReleasesList;
import cz.cuni.mff.releasemanager.utils.FileUtils;

/**
 * Abstract class for handling platform-specific operations.
 * This class provides methods for installing, uninstalling, and managing the list of installed releases.
 */
public abstract class PlatformHandler {
    protected static final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT);
    protected static final String RELEASES_LIST_FILE = "releases.json";
    protected static final String APP_DATA_DIR = "github-release-manager";
    /**
     * * Installs the asset.
     * @param asset Path to the asset to install.
     * @return Path to the installed application which is then used for uninstallation.
     */
    public abstract Path install(Path asset);
    /**
     * Uninstalls the asset.
     * @param asset Path to the asset to uninstall.
     * @return true if the asset is gone, false if it could not be removed.
     */
    public abstract boolean uninstall(Path asset);
    /**
     * @return Supported file formats for installation.
     */
    public abstract String[] getFormats();
    /**
     * @return Path to the directory where the config file is stored.
     */
    protected abstract Path getReleasesListDirLocation();


    /**
     * @return Path to the config file with a list of installed releases.
     */
    protected Path getReleasesListFileLocation() {
        return getReleasesListDirLocation().resolve(RELEASES_LIST_FILE);
    }

    /**
     * Checks whether a path is strictly inside a root directory.
     * @param path path to check
     * @param root allowed root directory
     * @return true if path is under root and is not root itself
     */
    protected static boolean isUnder(Path path, Path root) {
        if (path == null || root == null) {
            return false;
        }
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedRoot) && !normalizedPath.equals(normalizedRoot);
    }

    /**
     * Adds a release to the list of installed releases.
     * If file does not exist, it creates a new one.
     * If the release already exists, it updates the existing entry.
     * @param release ReleaseInfo object containing the information about the release to add.
     */
    public void addReleaseToList(ReleaseInfo release) throws IOException {
        ReleasesList releasesList = loadReleasesList();
        List<ReleaseInfo> currentReleases = releasesList == null
            ? new ArrayList<>()
            : new ArrayList<>(releasesList.releases());
        boolean found = false;
        for (int i = 0; i < currentReleases.size(); i++) {
            if (currentReleases.get(i).repo().equals(release.repo())) {
                currentReleases.set(i, release);
                found = true;
                break;
            }
        }
        if (!found) {
            currentReleases.add(release);
        }

        writeReleasesList(new ReleasesList(currentReleases));
    }

    /**
     * Loads the list of installed releases from the config file.
     * An empty file is treated as an empty list. A corrupt file is moved aside so it can be
     * inspected later and the application does not stay permanently broken.
     * @return ReleasesList object containing the list of installed releases.
     * @throws IOException
     */
    public ReleasesList loadReleasesList() throws IOException {
        Path releasesFile = getReleasesListFileLocation();
        if (!Files.exists(releasesFile) || Files.size(releasesFile) == 0) {
            return null;
        }
        ReleasesList releasesList;
        try {
            releasesList = mapper.readValue(releasesFile.toFile(), ReleasesList.class);
        } catch (JsonProcessingException e) {
            return quarantine(releasesFile);
        }
        if (releasesList == null || releasesList.releases() == null) {
            return null;
        }
        List<ReleaseInfo> validReleases = releasesList.releases().stream()
            .filter(PlatformHandler::isValid)
            .toList();
        if (validReleases.size() == releasesList.releases().size()) {
            return releasesList;
        }
        if (validReleases.isEmpty()) {
            return quarantine(releasesFile);
        }
        System.out.println("Warning: ignored "
            + (releasesList.releases().size() - validReleases.size())
            + " invalid release entries in " + releasesFile);
        return new ReleasesList(validReleases);
    }

    private static boolean isValid(ReleaseInfo release) {
        if (release == null
            || release.repo() == null
            || release.repo().isBlank()
            || release.uninstallPath() == null
            || release.uninstallPath().isBlank()
            || release.asset() == null) {
            return false;
        }
        var asset = release.asset();
        return asset.url() != null
            && !asset.url().isBlank()
            && asset.name() != null
            && !asset.name().isBlank();
    }

    private ReleasesList quarantine(Path releasesFile) throws IOException {
        Path quarantined = releasesFile.resolveSibling(
            RELEASES_LIST_FILE + ".corrupt-" + Instant.now().toEpochMilli());
        Files.move(releasesFile, quarantined, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Warning: releases file was corrupt; moved to " + quarantined);
        return null;
    }

    private void writeReleasesList(ReleasesList releasesList) throws IOException {
        Path releasesFile = getReleasesListFileLocation();
        Path dir = releasesFile.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, RELEASES_LIST_FILE + ".", ".tmp");
        try {
            mapper.writeValue(tmp.toFile(), releasesList);
            try {
                Files.move(tmp, releasesFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                Files.move(tmp, releasesFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Removes a release from the list of installed releases.
     * @param release ReleaseInfo object containing the information about the release to remove.
     * If the list is empty after removal, the file is deleted.
     * @return true if the release list was updated, false otherwise.
     */
    public boolean removeReleaseFromList(ReleaseInfo release) {
        try {
            ReleasesList releasesList = loadReleasesList();
            if (releasesList == null) {
                return false;
            }
            List<ReleaseInfo> currentReleases = new ArrayList<>(releasesList.releases());
            currentReleases.removeIf(r -> r.repo().equals(release.repo()));
            if (currentReleases.isEmpty()) {
                Files.deleteIfExists(getReleasesListFileLocation());
                FileUtils.removeTempDir(getReleasesListFileLocation());
                return true;
            }
            writeReleasesList(new ReleasesList(currentReleases));
            return true;
        } catch (IOException e) {
            System.out.println("Failed to remove release from list: " + e.getMessage());
            return false;
        }
    }
}
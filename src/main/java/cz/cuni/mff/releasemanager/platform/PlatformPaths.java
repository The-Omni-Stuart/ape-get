package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Paths;
import java.util.Objects;

import cz.cuni.mff.releasemanager.state.AppPaths;

// picks the state directory for the running OS
public final class PlatformPaths {

    private PlatformPaths() {}

    public static AppPaths current() {
        return forPlatform(
            Platform.detectPlatform().getOS(),
            System.getProperty("user.home"),
            System.getenv("LOCALAPPDATA"));
    }

    static AppPaths forPlatform(
        Platform.OS os, String userHome, String localAppData) {
        return switch (os) {
            case LINUX -> new AppPaths(
                Paths.get(userHome, ".local", "share", AppPaths.APP_DIR));
            case MAC -> new AppPaths(
                Paths.get(userHome, "Library", "Application Support", AppPaths.APP_DIR));
            case WINDOWS -> new AppPaths(
                Paths.get(
                    Objects.requireNonNull(localAppData, "LOCALAPPDATA is not set"),
                    AppPaths.APP_DIR));
        };
    }
}

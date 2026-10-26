package cz.cuni.mff.releasemanager.platform;

// picks the installer for the running OS
public final class PlatformHandlers {

    private PlatformHandlers() {}

    public static PlatformHandler current() {
        return switch (Platform.detectPlatform().getOS()) {
            case WINDOWS -> WindowsHandler.getInstance();
            case LINUX -> LinuxHandler.getInstance();
            case MAC -> MacHandler.getInstance();
        };
    }
}

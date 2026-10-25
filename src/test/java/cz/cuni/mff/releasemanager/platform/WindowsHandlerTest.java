package cz.cuni.mff.releasemanager.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WindowsHandlerTest {

    @Test
    void recognisesInstallerExtensionsRegardlessOfCase() {
        assertTrue(WindowsHandler.isMsi("App.MSI"));
        assertTrue(WindowsHandler.isMsi("app.msi"));
        assertTrue(WindowsHandler.isExe("Setup.EXE"));
        assertFalse(WindowsHandler.isMsi("app.exe"));
        assertFalse(WindowsHandler.isExe("app.msi"));
    }
}

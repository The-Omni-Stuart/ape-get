package cz.cuni.mff.releasemanager.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class PlatformTest {

    @Test
    void parsesArm64BeforeArm32() {
        assertEquals(Platform.Architecture.ARM_64, Platform.parseArch("aarch64"));
        assertEquals(Platform.Architecture.ARM_64, Platform.parseArch("arm64"));
    }

    @Test
    void parsesX86_64() {
        assertEquals(Platform.Architecture.X86_64, Platform.parseArch("x86_64"));
        assertEquals(Platform.Architecture.X86_64, Platform.parseArch("amd64"));
    }

    @Test
    void parses32BitArchitectures() {
        assertEquals(Platform.Architecture.X86_32, Platform.parseArch("x86"));
        assertEquals(Platform.Architecture.ARM_32, Platform.parseArch("armv7l"));
    }

    @Test
    void rejectsUnknownArchitecture() {
        assertThrows(IllegalStateException.class, () -> Platform.parseArch("mips"));
    }
}

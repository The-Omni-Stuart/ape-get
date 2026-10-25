package cz.cuni.mff.releasemanager.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;

@EnabledOnOs(OS.MAC)
class MacSignatureVerifierTest {

    @TempDir
    Path tempDir;

    private final ReleaseProvider provider = mock(ReleaseProvider.class);

    @Test
    @Timeout(300)
    void verifiesAdHocSignedBundle() throws Exception {
        Path dmg = buildDmg("Signed", true, false);

        SignatureResult result = new MacSignatureVerifier()
            .verify(provider, asset(dmg), List.of(), dmg);

        assertEquals(SignatureStatus.VERIFIED, result.status());
        assertEquals("ad-hoc", result.signer());
        assertNull(result.identity());
        assertEquals(Boolean.FALSE, result.notarized());
    }

    @Test
    @Timeout(300)
    void rejectsUnsignedBundle() throws Exception {
        Path dmg = buildDmg("Unsigned", false, false);

        SignatureResult result = new MacSignatureVerifier()
            .verify(provider, asset(dmg), List.of(), dmg);

        assertEquals(SignatureStatus.INVALID, result.status());
    }

    @Test
    @Timeout(300)
    void rejectsTamperedSignedBundle() throws Exception {
        Path dmg = buildDmg("Tampered", true, true);

        SignatureResult result = new MacSignatureVerifier()
            .verify(provider, asset(dmg), List.of(), dmg);

        assertEquals(SignatureStatus.INVALID, result.status());
    }

    private Path buildDmg(String name, boolean sign, boolean tamper) throws Exception {
        Path payload = Files.createDirectories(tempDir.resolve("payload-" + name));
        Path app = payload.resolve(name + ".app");
        Path contents = Files.createDirectories(app.resolve("Contents"));
        Path macos = Files.createDirectories(contents.resolve("MacOS"));
        Files.writeString(contents.resolve("Info.plist"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
              "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>CFBundleIdentifier</key><string>cz.cuni.mff.releasemanager.%s</string>
              <key>CFBundleName</key><string>%s</string>
              <key>CFBundleExecutable</key><string>%s</string>
              <key>CFBundlePackageType</key><string>APPL</string>
            </dict>
            </plist>
            """.formatted(name.toLowerCase(), name, name));
        Path executable = Files.writeString(macos.resolve(name),
            "#!/bin/sh\necho native\n");
        Files.setPosixFilePermissions(executable,
            PosixFilePermissions.fromString("rwxr-xr-x"));

        if (sign) {
            run(tempDir, "codesign", "--force", "--deep", "--sign", "-", app.toString());
            if (tamper) {
                Files.writeString(executable, "#!/bin/sh\necho tampered\n");
            }
        }

        Path dmg = tempDir.resolve(name + ".dmg");
        run(tempDir, "hdiutil", "create", "-volname", name,
            "-srcfolder", payload.toString(), "-ov", "-format", "UDZO", dmg.toString());
        return dmg;
    }

    private static Asset asset(Path dmg) {
        return new Asset(dmg.toUri().toString(), dmg.getFileName().toString());
    }

    private static void run(Path workingDirectory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .inheritIO()
            .start();
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, "command failed: " + String.join(" ", command));
    }
}

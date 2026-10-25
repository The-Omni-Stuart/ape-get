package cz.cuni.mff.releasemanager.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;

@EnabledOnOs(OS.LINUX)
class LinuxSignatureVerifierTest {

    @TempDir
    Path tempDir;

    private Path gnupgHome;
    private Path artifact;
    private Asset target;
    private Asset signatureAsset;
    private ReleaseProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        gnupgHome = Files.createDirectories(tempDir.resolve("gnupg"));
        Files.setPosixFilePermissions(gnupgHome,
            PosixFilePermissions.fromString("rwx------"));
        generateKey();

        artifact = Files.writeString(tempDir.resolve("app.AppImage"), "release artifact");
        target = new Asset("https://example.invalid/app.AppImage", "app.AppImage");
        signatureAsset = new Asset(
            "https://example.invalid/app.AppImage.sig", "app.AppImage.sig");
        provider = mock(ReleaseProvider.class);
    }

    @Test
    @Timeout(120)
    void verifiesDetachedSignature() throws Exception {
        Path signature = sign(artifact);
        when(provider.download(signatureAsset)).thenReturn(signature);

        SignatureResult result = new LinuxSignatureVerifier(gnupgHome)
            .verify(provider, target, List.of(target, signatureAsset), artifact);

        assertEquals(SignatureStatus.VERIFIED, result.status());
        assertEquals(fingerprint(), result.identity());
        assertTrue(result.signer().contains("ApeGet"));
    }

    @Test
    @Timeout(120)
    void rejectsTamperedArtifact() throws Exception {
        Path signature = sign(artifact);
        Files.writeString(artifact, "tampered");
        when(provider.download(signatureAsset)).thenReturn(signature);

        SignatureResult result = new LinuxSignatureVerifier(gnupgHome)
            .verify(provider, target, List.of(target, signatureAsset), artifact);

        assertEquals(SignatureStatus.INVALID, result.status());
        assertFalse(result.allowsInstall(false));
    }

    @Test
    void reportsMissingSignature() throws Exception {
        SignatureResult result = new LinuxSignatureVerifier(gnupgHome)
            .verify(provider, target, List.of(target), artifact);

        assertEquals(SignatureStatus.MISSING, result.status());
        verify(provider, never()).download(any());
    }

    private void generateKey() throws Exception {
        runGpg("--batch", "--pinentry-mode", "loopback", "--passphrase", "",
            "--quick-generate-key", "ApeGet Test <apeget@example.invalid>",
            "ed25519", "sign", "never");
    }

    private Path sign(Path file) throws Exception {
        Path signature = tempDir.resolve(file.getFileName() + ".asc");
        runGpg("--batch", "--armor", "--detach-sign",
            "--output", signature.toString(), file.toString());
        return signature;
    }

    private String fingerprint() throws Exception {
        return runGpg("--with-colons", "--fingerprint").lines()
            .filter(line -> line.startsWith("fpr:"))
            .map(line -> line.split(":")[9])
            .findFirst()
            .orElseThrow();
    }

    private String runGpg(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("gpg");
        command.add("--homedir");
        command.add(gnupgHome.toString());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, output);
        return output;
    }
}

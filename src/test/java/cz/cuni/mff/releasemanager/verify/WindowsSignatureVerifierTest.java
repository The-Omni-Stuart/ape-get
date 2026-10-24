package cz.cuni.mff.releasemanager.verify;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.types.Asset;

@EnabledOnOs(OS.WINDOWS)
class WindowsSignatureVerifierTest {

    @TempDir
    Path tempDir;

    private String thumbprint;

    @AfterEach
    void removeCertificate() throws Exception {
        if (thumbprint != null) {
            runPowerShell("Remove-Item 'Cert:\\CurrentUser\\My\\" + thumbprint
                + "' -Force -ErrorAction SilentlyContinue");
        }
    }

    @Test
    @Timeout(300)
    void verifiesTrustedSignature() throws Exception {
        Path trusted = findTrustedExecutable();
        assumeTrue(trusted != null, "no embedded Authenticode signature found");
        Path copy = Files.copy(trusted, tempDir.resolve("signed.exe"));

        SignatureResult result = new WindowsSignatureVerifier()
            .verify(asset(copy), List.of(), copy);

        System.out.println("authenticode: " + result);
        assertEquals(SignatureStatus.VERIFIED, result.status(), result.detail());
        assertNotNull(result.signer());
        assertNotNull(result.identity());
    }

    @Test
    @Timeout(300)
    void reportsUnsignedFile() throws Exception {
        Path executable = createUnsignedExecutable("unsigned.exe");

        SignatureResult result = new WindowsSignatureVerifier()
            .verify(asset(executable), List.of(), executable);

        System.out.println("authenticode: " + result);
        assertEquals(SignatureStatus.MISSING, result.status(), result.detail());
    }

    @Test
    @Timeout(300)
    void rejectsTamperedEmbeddedSignature() throws Exception {
        Path executable = createUnsignedExecutable("tampered.exe");
        thumbprint = signWithSelfSignedCertificate(executable);

        try (RandomAccessFile file = new RandomAccessFile(executable.toFile(), "rw")) {
            long offset = Math.max(0, file.length() / 2);
            file.seek(offset);
            int original = file.read();
            file.seek(offset);
            file.write(original ^ 0xff);
        }

        SignatureResult result = new WindowsSignatureVerifier()
            .verify(asset(executable), List.of(), executable);

        System.out.println("authenticode: " + result);
        assertEquals(SignatureStatus.INVALID, result.status(), result.detail());
        assertTrue(result.detail().contains("hash"), result.detail());
    }

    private Path createUnsignedExecutable(String name) throws Exception {
        Path executable = tempDir.resolve(name);
        runPowerShell("Add-Type -TypeDefinition"
            + " 'public class ApeGetFixture { }'"
            + " -OutputAssembly '" + executable + "' -OutputType Library");
        return executable;
    }

    private String signWithSelfSignedCertificate(Path executable) throws Exception {
        String output = runPowerShell(
            "$cert = New-SelfSignedCertificate -Type CodeSigningCert"
                + " -Subject 'CN=ApeGet Test Signer'"
                + " -CertStoreLocation Cert:\\CurrentUser\\My"
                + " -KeyExportPolicy Exportable -NotAfter (Get-Date).AddDays(1)"
                + "\nSet-AuthenticodeSignature -FilePath '" + executable
                + "' -Certificate $cert -HashAlgorithm SHA256 | Out-Null"
                + "\n$cert.Thumbprint");
        return output.lines()
            .map(String::trim)
            .filter(line -> line.matches("[0-9A-Fa-f]+"))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no certificate thumbprint:\n" + output));
    }

    private static Path findTrustedExecutable() throws Exception {
        String root = System.getenv("SystemRoot");
        String[] candidates = {
            root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
            root + "\\System32\\where.exe",
            root + "\\System32\\notepad.exe"
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            SignatureResult result = new WindowsSignatureVerifier()
                .verify(asset(path), List.of(), path);
            if (result.status() == SignatureStatus.VERIFIED) {
                return path;
            }
        }
        return null;
    }

    private static Asset asset(Path file) {
        return new Asset(file.toUri().toString(), file.getFileName().toString());
    }

    private static String runPowerShell(String script) throws Exception {
        Process process = startPowerShell(script);
        String output = new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, output);
        return output.strip();
    }

    private static Process startPowerShell(String script) throws Exception {
        try {
            return new ProcessBuilder(
                "pwsh", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .start();
        } catch (java.io.IOException e) {
            return new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .start();
        }
    }
}

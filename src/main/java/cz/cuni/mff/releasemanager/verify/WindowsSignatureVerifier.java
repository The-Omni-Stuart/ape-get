package cz.cuni.mff.releasemanager.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;

import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.ChildEnvironment;
import cz.cuni.mff.releasemanager.utils.Executables;
import cz.cuni.mff.releasemanager.utils.ProcessRunner;

// verifies Authenticode signatures on .exe and .msi files
public final class WindowsSignatureVerifier implements SignatureVerifier {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Authenticode(String Status, String Signer, String Thumbprint) {}

    @Override
    public List<Tool> tools() {
        return List.of(new Tool("pwsh", true, Executables.isAvailable("pwsh")));
    }

    @Override
    public SignatureResult verify(
        ReleaseProvider provider, Asset target, List<Asset> releaseAssets, Path downloaded)
        throws IOException, InterruptedException {
        String name = downloaded.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".exe") && !name.endsWith(".msi")) {
            return SignatureResult.unavailable(
                target.name(), "Authenticode applies to .exe and .msi only");
        }

        String script = "$s = Get-AuthenticodeSignature -LiteralPath " + quote(downloaded)
            + "; $cert = $s.SignerCertificate; $signer = $null; $thumb = $null;"
            + " if ($cert -ne $null) { $signer = $cert.Subject; $thumb = $cert.Thumbprint };"
            + " [pscustomobject]@{ Status = $s.Status.ToString();"
            + " Signer = $signer; Thumbprint = $thumb } | ConvertTo-Json -Compress";

        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(ChildEnvironment.sanitize(new ProcessBuilder(
                "pwsh", "-NoProfile", "-NonInteractive", "-Command", script)), TIMEOUT);
        } catch (IOException e) {
            return SignatureResult.unavailable(target.name(), "pwsh is not available");
        }

        if (result.timedOut()) {
            return SignatureResult.error(
                target.name(), "Authenticode verification timed out");
        }
        String output = result.output();
        Authenticode authenticode = parse(output);
        if (authenticode == null || authenticode.Status() == null) {
            return SignatureResult.error(
                target.name(),
                "unexpected Authenticode output (exit " + result.exitCode() + "): "
                    + output.strip());
        }
        return switch (authenticode.Status()) {
            case "Valid" -> SignatureResult.verified(
                target.name(), "authenticode", authenticode.Signer(), authenticode.Thumbprint());
            case "NotSigned" -> SignatureResult.missing(target.name());
            case "HashMismatch" -> SignatureResult.invalid(
                target.name(), "authenticode", "file hash does not match the signature");
            case "NotTrusted" -> SignatureResult.invalid(
                target.name(), "authenticode", "signing certificate is not trusted");
            default -> SignatureResult.invalid(
                target.name(), "authenticode", "signature status " + authenticode.Status());
        };
    }

    private static Authenticode parse(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return MAPPER.readValue(output.substring(start, end + 1), Authenticode.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }
}

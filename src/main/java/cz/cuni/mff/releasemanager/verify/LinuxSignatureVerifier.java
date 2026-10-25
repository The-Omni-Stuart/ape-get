package cz.cuni.mff.releasemanager.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.ChildEnvironment;

/**
 * Verifies a detached GPG signature using the local keyring.
 */
public final class LinuxSignatureVerifier implements SignatureVerifier {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Path gnupgHome;

    public LinuxSignatureVerifier() {
        this(null);
    }

    /**
     * @param gnupgHome optional GnuPG home directory, mainly for tests
     */
    public LinuxSignatureVerifier(Path gnupgHome) {
        this.gnupgHome = gnupgHome;
    }

    @Override
    public SignatureResult verify(
        ReleaseProvider provider, Asset target, List<Asset> releaseAssets, Path downloaded)
        throws IOException, InterruptedException {
        Optional<Asset> signatureAsset = SignatureAssetFinder.find(releaseAssets, target);
        if (signatureAsset.isEmpty()) {
            return SignatureResult.missing(target.name());
        }

        Path signature = provider.download(signatureAsset.get());
        try {
            return verifyWithGpg(target, signature, downloaded);
        } finally {
            provider.discardAsset(signature);
        }
    }

    private SignatureResult verifyWithGpg(Asset target, Path signature, Path artifact)
        throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("gpg");
        if (gnupgHome != null) {
            command.add("--homedir");
            command.add(gnupgHome.toString());
        }
        command.add("--batch");
        command.add("--status-fd=1");
        command.add("--verify");
        command.add(signature.toString());
        command.add(artifact.toString());

        Path log = Files.createTempFile("grm-gpg-", ".log");
        Process process;
        try {
            process = ChildEnvironment.sanitize(new ProcessBuilder(command))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        } catch (IOException e) {
            Files.deleteIfExists(log);
            return SignatureResult.unavailable(target.name(), "gpg is not installed");
        }

        try {
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return SignatureResult.error(target.name(), "gpg timed out");
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return SignatureResult.invalid(target.name(), "gpg", failureDetail(output));
            }

            String fingerprint = statusValue(output, "VALIDSIG");
            String signer = statusSigner(output);
            if (fingerprint == null) {
                return SignatureResult.invalid(
                    target.name(), "gpg", "gpg reported no valid signature");
            }
            return SignatureResult.verified(target.name(), "gpg", signer, fingerprint);
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static String statusValue(String output, String status) {
        String prefix = "[GNUPG:] " + status + " ";
        for (String line : output.lines().toList()) {
            if (line.startsWith(prefix)) {
                String rest = line.substring(prefix.length()).trim();
                if (rest.isEmpty()) {
                    return null;
                }
                return rest.split("\\s+")[0];
            }
        }
        return null;
    }

    private static String statusSigner(String output) {
        String prefix = "[GNUPG:] GOODSIG ";
        for (String line : output.lines().toList()) {
            if (line.startsWith(prefix)) {
                String rest = line.substring(prefix.length());
                int space = rest.indexOf(' ');
                return space < 0 ? null : rest.substring(space + 1);
            }
        }
        return null;
    }

    private static String failureDetail(String output) {
        for (String line : output.lines().toList()) {
            if (line.contains("BAD signature")) {
                return "BAD signature";
            }
            if (line.contains("NO_PUBKEY")) {
                return "public key not found";
            }
        }
        String last = output.lines()
            .filter(line -> !line.isBlank())
            .reduce((first, second) -> second)
            .orElse("gpg rejected the signature");
        return last.length() > 200 ? last.substring(0, 200) : last;
    }
}

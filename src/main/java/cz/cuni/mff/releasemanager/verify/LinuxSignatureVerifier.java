package cz.cuni.mff.releasemanager.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.ChildEnvironment;
import cz.cuni.mff.releasemanager.utils.Executables;
import cz.cuni.mff.releasemanager.utils.ProcessRunner;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

// verifies detached GPG signatures with the local keyring
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
    public List<Tool> tools() {
        return List.of(new Tool("gpg", true, Executables.isAvailable("gpg")));
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
            StagedAsset.discard(signature);
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

        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(
                ChildEnvironment.sanitize(new ProcessBuilder(command)), TIMEOUT);
        } catch (IOException e) {
            return SignatureResult.unavailable(target.name(), "gpg is not installed");
        }

        if (result.timedOut()) {
            return SignatureResult.error(target.name(), "gpg timed out");
        }
        if (result.exitCode() != 0) {
            return SignatureResult.invalid(
                target.name(), "gpg", failureDetail(result.output()));
        }

        String fingerprint = statusValue(result.output(), "VALIDSIG");
        String signer = statusSigner(result.output());
        if (fingerprint == null) {
            return SignatureResult.invalid(
                target.name(), "gpg", "gpg reported no valid signature");
        }
        return SignatureResult.verified(target.name(), "gpg", signer, fingerprint);
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

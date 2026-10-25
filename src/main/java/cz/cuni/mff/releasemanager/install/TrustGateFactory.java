package cz.cuni.mff.releasemanager.install;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.verify.AssetVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureVerifier;
import cz.cuni.mff.releasemanager.verify.TrustGate;

// wires the trust checks for one operation
public final class TrustGateFactory {
    private final CmdParser cmdParser;
    private final GrmConfig toolConfig;
    private final AssetVerifier verifier;
    private final SignatureVerifier signatureVerifier;
    private final Confirmer confirm;

    public TrustGateFactory(
        CmdParser cmdParser,
        GrmConfig toolConfig,
        AssetVerifier verifier,
        SignatureVerifier signatureVerifier,
        Confirmer confirm) {
        this.cmdParser = cmdParser;
        this.toolConfig = toolConfig;
        this.verifier = verifier;
        this.signatureVerifier = signatureVerifier;
        this.confirm = confirm;
    }

    public TrustGate create() {
        return new TrustGate(
            cmdParser, toolConfig, verifier, signatureVerifier, confirm);
    }
}

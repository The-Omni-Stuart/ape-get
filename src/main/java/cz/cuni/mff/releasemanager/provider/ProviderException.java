package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;

/**
 * A provider failure with a reason the CLI can report consistently.
 */
public final class ProviderException extends IOException {
    public enum Reason {
        UNSUPPORTED, RATE_LIMITED, UNAUTHORIZED, TIMEOUT, UNREACHABLE,
        NOT_FOUND, OTHER
    }

    private final Reason reason;

    public ProviderException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ProviderException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static Reason of(Throwable error) {
        return error instanceof ProviderException provider
            ? provider.reason() : Reason.OTHER;
    }
}

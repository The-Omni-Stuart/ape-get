package cz.cuni.mff.releasemanager.provider;

import java.util.List;

import cz.cuni.mff.releasemanager.utils.Output;

/**
 * One place for "this provider could not be searched or looked up" messages.
 */
public final class ProviderNotices {

    private ProviderNotices() {}

    public static void reportFailures(List<? extends ProviderFailure> failures) {
        if (failures == null) {
            return;
        }
        for (ProviderFailure failure : failures) {
            if (failure.error() == null
                || failure.reason() == ProviderException.Reason.NOT_FOUND) {
                continue;
            }
            report(failure.provider(), failure.error(), failure.reason());
        }
    }

    public static void reportFailure(String provider, Throwable error) {
        ProviderException.Reason reason = error == null
            ? ProviderException.Reason.OTHER : ProviderException.of(error);
        if (reason == ProviderException.Reason.NOT_FOUND) {
            return;
        }
        String message = error == null ? "unknown error" : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        report(provider, message, reason);
    }

    private static void report(
        String provider, String message, ProviderException.Reason reason) {
        boolean unsupported = reason == ProviderException.Reason.UNSUPPORTED;
        String label = unsupported ? "Note" : "Warning";
        String hint = unsupported ? " (use provider:owner/repo)" : "";
        Output.error(label + ": " + provider + ": " + message + hint);
    }
}

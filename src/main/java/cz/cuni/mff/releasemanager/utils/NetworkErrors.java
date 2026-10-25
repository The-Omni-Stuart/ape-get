package cz.cuni.mff.releasemanager.utils;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

/**
 * Turns network failures into a short reason even when the exception has no message.
 */
public final class NetworkErrors {

    private NetworkErrors() {}

    public static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (error instanceof HttpTimeoutException) {
            return "the request timed out";
        }
        if (error instanceof UnknownHostException) {
            return "could not resolve the host";
        }
        if (error instanceof ConnectException) {
            return "could not connect";
        }
        if (error instanceof InterruptedIOException) {
            return "the transfer was interrupted";
        }
        return error.getClass().getSimpleName();
    }
}

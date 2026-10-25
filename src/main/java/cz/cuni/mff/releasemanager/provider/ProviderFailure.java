package cz.cuni.mff.releasemanager.provider;

/**
 * One provider that could not complete a search or lookup.
 */
public interface ProviderFailure {
    String provider();

    String error();

    ProviderException.Reason reason();
}

package cz.cuni.mff.releasemanager.install;

// outcome of one update attempt
public record UpdateResult(
    String provider,
    String repo,
    String fromVersion,
    String toVersion,
    Status status,
    String message
) {
    public enum Status {
        UPDATED, UP_TO_DATE, PLANNED, FAILED
    }

    public String statusId() {
        return status.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}

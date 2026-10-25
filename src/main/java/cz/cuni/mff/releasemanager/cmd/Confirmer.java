package cz.cuni.mff.releasemanager.cmd;

// asks before an operation that cannot be undone
@FunctionalInterface
public interface Confirmer {
    boolean confirm(String message);
}

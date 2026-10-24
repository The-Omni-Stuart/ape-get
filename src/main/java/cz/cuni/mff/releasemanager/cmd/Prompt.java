package cz.cuni.mff.releasemanager.cmd;

/**
 * Asks the user to confirm an unsafe operation.
 */
public interface Prompt {
    /**
     * @param message explanation of the operation
     * @param assumeYes true when --yes was supplied
     * @return true if the operation may proceed
     */
    boolean confirm(String message, boolean assumeYes);
}

package cz.cuni.mff.releasemanager.cmd;

import cz.cuni.mff.releasemanager.utils.Output;

/**
 * Formats the confirmation prompt for a state-changing action.
 */
public final class Confirmation {
    private final Confirmer confirmer;
    private final CmdParser cmdParser;

    public Confirmation(Confirmer confirmer, CmdParser cmdParser) {
        this.confirmer = confirmer;
        this.cmdParser = cmdParser;
    }

    /**
     * @param action short verb, for example "Install" or "Uninstall"
     * @param detail what will be changed
     * @return true when the action may proceed
     */
    public boolean allowed(String action, String detail) {
        if (cmdParser.isDryRun()) {
            return true;
        }
        String message = detail == null || detail.isBlank()
            ? action + "?" : action + " " + detail + "?";
        if (confirmer.confirm(message)) {
            return true;
        }
        Output.error(action + " declined.");
        return false;
    }
}

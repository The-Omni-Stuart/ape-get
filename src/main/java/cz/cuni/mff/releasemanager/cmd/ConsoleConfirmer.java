package cz.cuni.mff.releasemanager.cmd;

// ties the prompt to the --yes flag for the current command
public final class ConsoleConfirmer implements Confirmer {
    private final Prompt prompt;
    private final CmdParser cmdParser;

    public ConsoleConfirmer(Prompt prompt, CmdParser cmdParser) {
        this.prompt = prompt;
        this.cmdParser = cmdParser;
    }

    @Override
    public boolean confirm(String message) {
        return prompt.confirm(message, cmdParser.isAssumeYes());
    }
}

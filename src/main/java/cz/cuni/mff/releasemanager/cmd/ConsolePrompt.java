package cz.cuni.mff.releasemanager.cmd;

import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.function.Supplier;

import cz.cuni.mff.releasemanager.utils.Output;

/**
 * Interactive yes/no prompt with non-TTY and --yes handling.
 */
public final class ConsolePrompt implements Prompt {
    private final Supplier<String> input;
    private final boolean interactive;

    public ConsolePrompt() {
        this(new Scanner(System.in)::nextLine, System.console() != null);
    }

    public ConsolePrompt(CmdParser cmdParser) {
        this(cmdParser, System.console() != null);
    }

    ConsolePrompt(CmdParser cmdParser, boolean interactive) {
        this(cmdParser::nextLine, interactive);
    }

    ConsolePrompt(Supplier<String> input, boolean interactive) {
        this.input = input;
        this.interactive = interactive;
    }

    @Override
    public boolean confirm(String message, boolean assumeYes) {
        if (assumeYes) {
            return true;
        }
        Output.error(message);
        if (!interactive) {
            Output.error(
                "Confirmation required. Re-run with --yes (or -y) to approve.");
            return false;
        }
        Output.prompt("Continue? [y/N] ");
        try {
            String answer = input.get();
            if (answer == null) {
                return false;
            }
            String normalized = answer.trim().toLowerCase();
            return normalized.equals("y") || normalized.equals("yes");
        } catch (NoSuchElementException e) {
            return false;
        }
    }
}

package cz.cuni.mff.releasemanager.cmd;

import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.function.Supplier;

/**
 * Interactive yes/no prompt with non-TTY and --yes handling.
 */
public final class ConsolePrompt implements Prompt {
    private final Supplier<String> input;
    private final boolean interactive;

    public ConsolePrompt() {
        this(new Scanner(System.in)::nextLine, System.console() != null);
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
        System.err.println(message);
        if (!interactive) {
            System.err.println("Confirmation required. Re-run with --yes to approve.");
            return false;
        }
        System.out.print("Continue? [y/N] ");
        System.out.flush();
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

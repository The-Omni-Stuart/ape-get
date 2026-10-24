package cz.cuni.mff.releasemanager.cmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * Command line parser for the application.
 * This class is responsible for parsing command line arguments and user input.
 */
public class CmdParser {

    private final Scanner scanner = new Scanner(System.in);
    private boolean strict;
    private boolean assumeYes;
    private boolean requireSignature;
    private boolean allowSignerChange;

    /**
     * Parses the command line arguments and returns the corresponding Command.
     *
     * @param args command line arguments
     * @return the parsed Command
     */
    public Command parse(String[] args) {
        strict = false;
        assumeYes = false;
        requireSignature = false;
        allowSignerChange = false;
        List<String> filtered = new ArrayList<>(args.length);
        for (String arg : args) {
            if ("--strict".equals(arg)) {
                strict = true;
            } else if ("--yes".equals(arg)) {
                assumeYes = true;
            } else if ("--require-signature".equals(arg)) {
                requireSignature = true;
            } else if ("--allow-signer-change".equals(arg)) {
                allowSignerChange = true;
            } else {
                filtered.add(arg);
            }
        }
        args = filtered.toArray(String[]::new);

        Command.HELP.argument = null;
        Command.USAGE.argument = null;
        if (args.length == 0) {
            return Command.HELP;
        }
        Command command = Command.fromName(args[0]).orElse(null);
        if (command == null) {
            return usage(String.join(" ", args));
        }
        if (command == Command.SEARCH) {
            if (args.length < 2) {
                return usage(null);
            }
            String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            if (query.isBlank()) {
                return usage(null);
            }
            command.argument = query;
            return command;
        }
        if (command.requiresArgument()) {
            if (args.length != 2) {
                return usage(null);
            }
            command.argument = args[1];
            return command;
        }
        if (args.length != 1) {
            return usage(null);
        }
        return command;
    }

    private Command usage(String message) {
        Command.USAGE.argument = message;
        return Command.USAGE;
    }

    /**
     * @return true if the last parse saw --strict
     */
    public boolean isStrict() {
        return strict;
    }

    /**
     * @return true if the last parse saw --yes
     */
    public boolean isAssumeYes() {
        return assumeYes;
    }

    /**
     * @return true if the last parse saw --require-signature
     */
    public boolean isRequireSignature() {
        return requireSignature;
    }

    /**
     * @return true if the last parse saw --allow-signer-change
     */
    public boolean isAllowSignerChange() {
        return allowSignerChange;
    }

    /**
     * Prompts the user for input and returns the input as an integer.
     * This method handles invalid input by recursively calling itself until an integer is entered.
     * @return the user input as an integer
     */
    public int getUserInput() {
        try {
            String input = scanner.nextLine();
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            System.err.println("Invalid input. Please enter a number.");
            return getUserInput();
        }
    }

}

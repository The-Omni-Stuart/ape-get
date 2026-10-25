package cz.cuni.mff.releasemanager.cmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import cz.cuni.mff.releasemanager.utils.Output;

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
    private boolean dryRun;
    private boolean json;
    private boolean quiet;
    private String provider;
    private String asset;
    private String version;
    private String kind;
    private String commandName;
    private boolean noCommand;
    private boolean assimilate;
    private boolean desktop;
    private boolean noDesktop;
    private boolean terminal;
    private String icon;
    private boolean all;
    private boolean outdated;
    private String channel;

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
        dryRun = false;
        json = false;
        quiet = false;
        provider = null;
        asset = null;
        version = null;
        kind = null;
        commandName = null;
        noCommand = false;
        assimilate = false;
        desktop = false;
        noDesktop = false;
        terminal = false;
        icon = null;
        all = false;
        outdated = false;
        channel = null;
        List<String> filtered = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--strict".equals(arg)) {
                strict = true;
            } else if ("--yes".equals(arg)) {
                assumeYes = true;
            } else if ("--require-signature".equals(arg)) {
                requireSignature = true;
            } else if ("--allow-signer-change".equals(arg)) {
                allowSignerChange = true;
            } else if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else if ("--json".equals(arg)) {
                json = true;
            } else if ("--quiet".equals(arg)) {
                quiet = true;
            } else if ("--all".equals(arg)) {
                all = true;
            } else if ("--outdated".equals(arg)) {
                outdated = true;
            } else if ("--channel".equals(arg)) {
                if (i + 1 >= args.length) {
                    return usage("Missing value for --channel");
                }
                channel = args[++i];
            } else if ("--provider".equals(arg)) {
                if (i + 1 >= args.length) {
                    return usage("Missing value for --provider");
                }
                provider = args[++i];
            } else if ("--asset".equals(arg)) {
                if (i + 1 >= args.length) {
                    return usage("Missing value for --asset");
                }
                asset = args[++i];
            } else if ("--version".equals(arg)) {
                if (i + 1 >= args.length) {
                    return usage("Missing value for --version");
                }
                version = args[++i];
            } else if ("--kind".equals(arg)) {
                if (i + 1 >= args.length) {
                    return usage("Missing value for --kind");
                }
                kind = args[++i];
            } else if ("--name".equals(arg)) {
                if (i + 1 >= args.length) {
                    return usage("Missing value for --name");
                }
                commandName = args[++i];
            } else if ("--no-command".equals(arg)) {
                noCommand = true;
            } else if ("--assimilate".equals(arg)) {
                assimilate = true;
            } else if ("--desktop".equals(arg)) {
                desktop = true;
            } else if ("--no-desktop".equals(arg)) {
                noDesktop = true;
            } else if ("--terminal".equals(arg)) {
                terminal = true;
            } else if ("--icon".equals(arg)) {
                if (i + 1 >= args.length) {
                    return usage("Missing value for --icon");
                }
                icon = args[++i];
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
        if (command == Command.PROVIDERS) {
            command.arguments = List.of(Arrays.copyOfRange(args, 1, args.length));
            return command;
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
        if (command == Command.UPDATE && all) {
            if (args.length != 1) {
                return usage("--all does not take a repository");
            }
            command.argument = null;
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
     * @return provider name from --provider, or null
     */
    public String getProvider() {
        return provider;
    }

    /**
     * @return asset name from --asset, or null
     */
    public String getAsset() {
        return asset;
    }

    /**
     * @return release tag from --version, or null
     */
    public String getVersion() {
        return version;
    }

    /**
     * @return package kind from --kind, or null for auto
     */
    public String getKind() {
        return kind;
    }

    /**
     * @return command name from --name, or null
     */
    public String getCommandName() {
        return commandName;
    }

    /**
     * @return true when --no-command was supplied
     */
    public boolean isNoCommand() {
        return noCommand;
    }

    /**
     * @return true when --assimilate was supplied
     */
    public boolean isAssimilate() {
        return assimilate;
    }

    /**
     * @return true when --desktop was supplied
     */
    public boolean isDesktop() {
        return desktop;
    }

    /**
     * @return true when --no-desktop was supplied
     */
    public boolean isNoDesktop() {
        return noDesktop;
    }

    /**
     * @return true when --terminal was supplied
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * @return icon path from --icon, or null
     */
    public String getIcon() {
        return icon;
    }

    /**
     * @return true if the last parse saw --dry-run
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * @return true if the last parse saw --json
     */
    public boolean isJson() {
        return json;
    }

    /**
     * @return true if the last parse saw --quiet
     */
    public boolean isQuiet() {
        return quiet;
    }

    /**
     * @return true when --all was supplied
     */
    public boolean isAll() {
        return all;
    }

    /**
     * @return true when --outdated was supplied
     */
    public boolean isOutdated() {
        return outdated;
    }

    /**
     * @return release channel from --channel, or null
     */
    public String getChannel() {
        return channel;
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
            Output.error("Invalid input. Please enter a number.");
            return getUserInput();
        }
    }

}

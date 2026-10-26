package cz.cuni.mff.releasemanager.commands;

import java.io.PrintStream;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.utils.Output;

// command help and usage text
public final class Usage {

    private Usage() {}

    public static void help() {
        print(System.out);
    }

    public static int unknown(Command command) {
        if (command.argument != null && !command.argument.isBlank()) {
            Output.error("Unknown command: " + command.argument);
        }
        print(System.err);
        return 2;
    }

    private static void print(PrintStream out) {
        out.println("Usage: java -jar github-releases-manager.jar [command] [options]");
        out.println("Commands:");
        out.println("  search [name] - search for a release by name");
        out.println("  install [name] - install provider:owner/repo,"
            + " or a bare owner/repo across providers");
        out.println("  uninstall [name] - uninstall the release by name");
        out.println("  update [name] - update one release, or use --all");
        out.println("  list - list all installed releases");
        out.println("  info [name] - show details for an installed release");
        out.println("  providers - list, add, update, remove, or recover provider instances");
        out.println("  rollback [name] - restore the latest backup");
        out.println("  doctor - diagnose state and orphaned installs");
        out.println("  adopt [name] - adopt an existing Linux install");
        out.println("  help - display this help message");
        out.println("Options:");
        out.println("  --strict - fail when a checksum is missing or unusable");
        out.println("  --require-signature - fail when no verified signature is available");
        out.println("  --allow-signer-change - accept a changed signer after confirmation");
        out.println("  --provider <name> - use a configured provider for search or install");
        out.println("  --asset <name|index> - choose a release asset non-interactively");
        out.println("  --kind <kind> - force appimage, portable-exe, ape, nsis, msi, or dmg");
        out.println("  --name <command> - command name for a portable package");
        out.println("  --no-command - install without creating a command");
        out.println("  --desktop - create a desktop entry (Linux)");
        out.println("  --no-desktop - remove an existing desktop entry");
        out.println("  --terminal - run the desktop entry in a terminal");
        out.println("  --icon <path> - icon for the desktop entry");
        out.println("  --assimilate - convert an APE with the cosmocc assimilate tool");
        out.println("  --version <tag> - install or update to a specific release");
        out.println("  --all - update every installed package");
        out.println("  --outdated - list only packages with newer releases");
        out.println("  --channel <stable|beta> - choose the release channel");
        out.println("  --dry-run - show what would happen without changing anything");
        out.println("  --json - machine-readable output");
        out.println("  --quiet - suppress progress messages");
        out.println("  -y, --yes - assume yes for confirmation prompts");
    }
}

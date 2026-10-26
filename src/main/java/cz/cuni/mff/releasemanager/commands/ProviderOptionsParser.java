package cz.cuni.mff.releasemanager.commands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// parses --flag and --value options for the provider subcommands
final class ProviderOptionsParser {

    record Options(
        Map<String, String> values,
        List<String> positional,
        Set<String> flags
    ) {}

    private ProviderOptionsParser() {}

    static Options parse(
        List<String> args, Set<String> flagNames, Set<String> valueNames) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        Set<String> flags = new HashSet<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("--")) {
                positional.add(arg);
                continue;
            }
            String name = arg.substring(2);
            if (flagNames.contains(name)) {
                flags.add(name);
            } else if (valueNames.contains(name)) {
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException("Missing value for --" + name);
                }
                values.put(name, args.get(++i));
            } else {
                throw new IllegalArgumentException("Unknown option: --" + name);
            }
        }
        return new Options(values, positional, flags);
    }
}

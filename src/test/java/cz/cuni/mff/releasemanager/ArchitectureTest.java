package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureTest {

    private static final Path SOURCES = Path.of(
        "src", "main", "java", "cz", "cuni", "mff", "releasemanager");
    private static final String PREFIX = "cz.cuni.mff.releasemanager.";

    @Test
    void stateDoesNotDependOnPlatformOrProvider() throws IOException {
        assertNoImports("state", "platform", "provider");
    }

    @Test
    void platformDoesNotDependOnProviderInstallOrCommands() throws IOException {
        assertNoImports("platform", "provider", "install", "commands");
    }

    @Test
    void providerDoesNotDependOnInstallOrCommands() throws IOException {
        assertNoImports("provider", "install", "commands");
    }

    private static void assertNoImports(String pkg, String... forbidden)
        throws IOException {
        List<String> imports;
        try (Stream<Path> files = Files.walk(SOURCES.resolve(pkg))) {
            imports = files
                .filter(path -> path.toString().endsWith(".java"))
                .flatMap(ArchitectureTest::importsOf)
                .toList();
        }
        List<String> blocked = List.of(forbidden);
        List<String> offenders = imports.stream()
            .filter(line -> blocked.stream()
                .anyMatch(other -> line.startsWith("import " + PREFIX + other + ".")))
            .toList();

        assertTrue(offenders.isEmpty(), pkg + " imports " + offenders);
    }

    private static Stream<String> importsOf(Path file) {
        try {
            return Files.readAllLines(file).stream()
                .filter(line -> line.startsWith("import "));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }
}

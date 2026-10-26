package cz.cuni.mff.releasemanager.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;




public class FileUtilsTest {

    private static final String TEST_FILE = "TestFile123.txt";
    private static final String TEST_DIR = "tempTestDir";

    @Test
    public void sanitizeVersionKeepsOneSafePathSegment() {
        assertEquals("v1.2.3", FileUtils.sanitizeVersion("v1.2.3"));
        assertEquals("v1-2", FileUtils.sanitizeVersion("v1/2"));
        assertEquals("v1.2", FileUtils.sanitizeVersion("v1..2"));
        assertEquals("1.0", FileUtils.sanitizeVersion("...1.0"));
        assertEquals("unknown", FileUtils.sanitizeVersion(".."));
        assertEquals("unknown", FileUtils.sanitizeVersion("  "));
        assertEquals("unknown", FileUtils.sanitizeVersion(null));
    }

    @Test
    public void testCopyRecursively() throws IOException {
        Path source = Files.createTempDirectory(TEST_DIR);
        Path target = source.resolveSibling("copy-" + System.nanoTime());
        Path nested = Files.createDirectories(source.resolve("a/b"));
        Files.writeString(nested.resolve("file.txt"), "data");

        FileUtils.copyRecursively(source, target);

        assertEquals("data", Files.readString(target.resolve("a/b/file.txt")));

        FileUtils.deleteRecursively(source);
        FileUtils.deleteRecursively(target);
    }

    @Test
    public void testDeleteRecursively() throws IOException {
        Path dir = Files.createTempDirectory(TEST_DIR);
        Path nested = Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(nested.resolve("file.txt"), "data");

        FileUtils.deleteRecursively(dir);

        assertFalse(Files.exists(dir));
    }

    @Test
    public void testReplaceFileReplacesExistingTarget() throws IOException {
        Path dir = Files.createTempDirectory(TEST_DIR);
        Path source = Files.writeString(dir.resolve("new.txt"), "new");
        Path target = Files.writeString(dir.resolve("target.txt"), "old");

        FileUtils.replaceFile(source, target);

        assertFalse(Files.exists(source));
        assertEquals("new", Files.readString(target));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }

        FileUtils.deleteRecursively(dir);
    }

    @Test
    public void testReplaceFileMovesWhenTargetMissing() throws IOException {
        Path dir = Files.createTempDirectory(TEST_DIR);
        Path source = Files.writeString(dir.resolve("new.txt"), "new");
        Path target = dir.resolve("target.txt");

        FileUtils.replaceFile(source, target);

        assertEquals("new", Files.readString(target));

        FileUtils.deleteRecursively(dir);
    }

    @Test
    public void testReplaceFileRestoresTargetWhenSourceMissing() throws IOException {
        Path dir = Files.createTempDirectory(TEST_DIR);
        Path source = dir.resolve("missing.txt");
        Path target = Files.writeString(dir.resolve("target.txt"), "old");

        assertThrows(IOException.class, () -> FileUtils.replaceFile(source, target));

        assertEquals("old", Files.readString(target));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }

        FileUtils.deleteRecursively(dir);
    }

    @Test
    public void testReplaceDirectoryReplacesExistingTarget() throws IOException {
        Path source = Files.createTempDirectory(TEST_DIR);
        Path target = Files.createTempDirectory(TEST_DIR);
        Files.writeString(source.resolve("new.txt"), "new");
        Files.writeString(target.resolve("old.txt"), "old");

        FileUtils.replaceDirectory(source, target);

        assertFalse(Files.exists(source));
        assertFalse(Files.exists(target.resolve("old.txt")));
        assertEquals("new", Files.readString(target.resolve("new.txt")));

        FileUtils.deleteRecursively(target);
    }

    @Test
    public void testReplaceDirectoryMovesWhenTargetMissing() throws IOException {
        Path source = Files.createTempDirectory(TEST_DIR);
        Path target = source.resolveSibling("target-" + System.nanoTime());
        Files.writeString(source.resolve("new.txt"), "new");

        FileUtils.replaceDirectory(source, target);

        assertTrue(Files.exists(target.resolve("new.txt")));

        FileUtils.deleteRecursively(target);
    }

    @Test
    public void testReplaceDirectoryRestoresTargetWhenSourceMissing() throws IOException {
        Path parent = Files.createTempDirectory(TEST_DIR);
        Path source = parent.resolve("missing");
        Path target = Files.createDirectories(parent.resolve("target"));
        Files.writeString(target.resolve("old.txt"), "old");

        assertThrows(IOException.class, () -> FileUtils.replaceDirectory(source, target));

        assertTrue(Files.exists(target.resolve("old.txt")));
        try (var files = Files.list(parent)) {
            assertEquals(1, files.count());
        }

        FileUtils.deleteRecursively(parent);
    }

    @Test
    public void testInstallName_alwaysIncludesProvider() {
        assertEquals("github__owner__repo", FileUtils.installName("github", "owner/repo"));
        assertEquals("gitlab__owner__repo", FileUtils.installName("gitlab", "owner/repo"));
        assertEquals("gitlab__group__sub__project",
            FileUtils.installName("gitlab", "group/sub/project"));
    }

    @Test
    public void testInstallName_isNeverEmpty() {
        assertEquals("github__ip7z__7zip",
            FileUtils.installName("github", "ip7z/7zip"));
        assertFalse(FileUtils.installName("github", "/").isBlank());
        assertFalse(FileUtils.installName(null, "CON").isBlank());
    }

    @Test
    public void testInstallName_separatesUnderscoreLookalikes() {
        assertNotEquals(
            FileUtils.installName("gitlab", "a/b__c"),
            FileUtils.installName("gitlab", "a__b/c"));
    }

    @Test
    public void testRemoveTempDir() throws IOException {
        Path dir = Files.createTempDirectory(TEST_DIR);
        Path file = dir.resolve(TEST_FILE);
        Files.createFile(file);
        Files.delete(file);
        FileUtils.removeTempDir(file);

        assertFalse(Files.exists(dir));

        Files.deleteIfExists(dir);
    }

    @Test
    public void testRemoveTempDir_dirIsNotEmpty() throws IOException {
        Path dir = Files.createTempDirectory(TEST_DIR);
        Path file1 = dir.resolve("dummy1.txt");
        Path file2 = dir.resolve("dummy2.txt");
        Files.createFile(file1);
        Files.createFile(file2);
        FileUtils.removeTempDir(file1);

        assertTrue(Files.exists(dir));

        Files.deleteIfExists(file1);
        Files.deleteIfExists(file2);
        Files.deleteIfExists(dir);
    }
}

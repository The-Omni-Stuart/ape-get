package cz.cuni.mff.releasemanager.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;




public class FileUtilsTest {

    private static final String TEST_FILE = "TestFile123.txt";
    private static final String TEST_DIR = "tempTestDir";

    @Test
    public void testSaveInputStreamToFile() throws IOException {
        String content = "Hello, world!";
        InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        Path savedPath = FileUtils.saveInputStreamToFile(stream, TEST_FILE);

        assertNotNull(savedPath);
        assertTrue(Files.exists(savedPath));
        assertTrue(savedPath.getParent().startsWith(Paths.get(System.getProperty("java.io.tmpdir"))));
        assertEquals(content, Files.readString(savedPath));

        Files.deleteIfExists(savedPath);
        Files.deleteIfExists(savedPath.getParent());
    }

    @Test
    public void testSaveInputStreamToFile_throwsOnEmptyStream() {
        InputStream stream = new ByteArrayInputStream(new byte[0]);

        assertThrows(IOException.class, () -> FileUtils.saveInputStreamToFile(stream, TEST_FILE));
    }

    @Test
    public void testSaveInputStreamToFile_throwsWhenReadFails() {
        InputStream stream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };

        assertThrows(IOException.class, () -> FileUtils.saveInputStreamToFile(stream, TEST_FILE));
    }

    @Test
    public void testSaveInputStreamToFile_rejectsPathTraversal() {
        InputStream stream = new ByteArrayInputStream(new byte[] {1});

        assertThrows(IOException.class, () -> FileUtils.saveInputStreamToFile(stream, "../escape"));
    }

    @Test
    public void testGetShortCut_withAlphaPrefix() {
        Path file = Paths.get("Release_v1.0.txt");
        String shortcut = FileUtils.getShortCut(file);

        assertEquals("release", shortcut);
    }

    @Test
    public void testGetShortCut_withoutAlphaPrefix() {
        Path file = Paths.get("123file.txt");
        String shortcut = FileUtils.getShortCut(file);

        assertEquals("", shortcut);
    }

    @Test
    public void testGetShortCut_emptyFileName() {
        Path file = Paths.get("");
        String shortcut = FileUtils.getShortCut(file);

        assertEquals("", shortcut);
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
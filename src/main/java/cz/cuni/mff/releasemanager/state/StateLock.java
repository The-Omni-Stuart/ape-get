package cz.cuni.mff.releasemanager.state;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Exclusive lock for stateful commands so two instances cannot mutate state at once.
 */
public final class StateLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private StateLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static StateLock acquire(Path lockFile) throws IOException {
        Files.createDirectories(lockFile.getParent());
        FileChannel channel = FileChannel.open(
            lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                throw new IOException("Another instance is already running.");
            }
            return new StateLock(channel, lock);
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // preserve the original failure
            }
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}

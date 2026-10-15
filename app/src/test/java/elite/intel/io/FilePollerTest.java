package elite.intel.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What counts as a change: a write, a creation, a deletion, a file entering or leaving the watched set - and the
 * first poll counting nothing.
 */
class FilePollerTest {

    private static final Instant BEFORE = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-10-01T12:00:05Z");

    @TempDir
    Path dir;

    @Test
    void theFirstPollOnlyRecords() throws IOException {
        Path file = write("DeviceMappings.xml", "<Root/>", BEFORE);
        FilePoller poller = new FilePoller(() -> Set.of(file));

        assertEquals(Set.of(), poller.poll());
    }

    @Test
    void nothingWrittenIsNothingChanged() throws IOException {
        Path file = write("DeviceMappings.xml", "<Root/>", BEFORE);
        FilePoller poller = new FilePoller(() -> Set.of(file));
        poller.poll();

        assertEquals(Set.of(), poller.poll());
    }

    @Test
    void aWriteIsAChange() throws IOException {
        Path file = write("DeviceMappings.xml", "<Root/>", BEFORE);
        FilePoller poller = new FilePoller(() -> Set.of(file));
        poller.poll();

        write("DeviceMappings.xml", "<Root/>", AFTER);

        assertEquals(Set.of(file), poller.poll());
        assertEquals(Set.of(), poller.poll(), "reported once, not on every poll after");
    }

    /** A clock too coarse to move between two writes - FAT keeps two seconds - still shows a change of size. */
    @Test
    void aWriteInsideTheSameTimestampIsSeenByItsSize() throws IOException {
        Path file = write("LVWAP.buttonMap", "<Root/>", BEFORE);
        FilePoller poller = new FilePoller(() -> Set.of(file));
        poller.poll();

        write("LVWAP.buttonMap", "<Root><Joy_1>Trigger</Joy_1></Root>", BEFORE);

        assertEquals(Set.of(file), poller.poll());
    }

    @Test
    void aFileCreatedOrDeletedIsAChange() throws IOException {
        Path file = dir.resolve("DeviceMappings.xml");
        FilePoller poller = new FilePoller(() -> Set.of(file));
        poller.poll();

        write("DeviceMappings.xml", "<Root/>", BEFORE);
        assertEquals(Set.of(file), poller.poll(), "created");

        Files.delete(file);
        assertEquals(Set.of(file), poller.poll(), "deleted");
    }

    /** A new installation's files, or a .buttonMap no longer listed, are followed without being told. */
    @Test
    void filesEnteringOrLeavingTheWatchedSetAreChanges() throws IOException {
        Path first = write("A.buttonMap", "<Root/>", BEFORE);
        Path second = write("B.buttonMap", "<Root/>", BEFORE);
        Set<Path> watched = new LinkedHashSet<>(Set.of(first));
        FilePoller poller = new FilePoller(() -> Set.copyOf(watched));
        poller.poll();

        watched.add(second);
        assertEquals(Set.of(second), poller.poll(), "entered");

        watched.remove(first);
        assertEquals(Set.of(first), poller.poll(), "left");
    }

    @Test
    void onlyTheFileThatChangedIsReported() throws IOException {
        Path untouched = write("A.buttonMap", "<Root/>", BEFORE);
        Path written = write("B.buttonMap", "<Root/>", BEFORE);
        FilePoller poller = new FilePoller(() -> Set.of(untouched, written));
        poller.poll();

        write("B.buttonMap", "<Root/>", AFTER);

        assertEquals(Set.of(written), poller.poll());
    }

    private Path write(String name, String content, Instant modified) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }
}

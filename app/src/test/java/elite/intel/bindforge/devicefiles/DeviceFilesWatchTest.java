package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.devicefiles.DeviceFilesPush.Target;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the watch looks at - each installation's {@code DeviceMappings.xml} and its {@code .buttonMap}s, and nothing
 * else - and that it says so once per poll that found a change.
 */
class DeviceFilesWatchTest {

    private static final Instant BEFORE = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-10-01T12:00:05Z");
    /** Long enough that the schedule never polls during a test that drives {@code poll()} itself. */
    private static final Duration NEVER = Duration.ofHours(1);

    @TempDir
    Path dir;

    private final AtomicInteger told = new AtomicInteger();
    private DeviceFilesWatch watch;

    @AfterEach
    void stopIt() {
        if (watch != null) watch.stop();
    }

    @Test
    void aGameSaveOfDeviceMappingsIsReported() throws IOException {
        Path steam = install("steam");
        Path mappings = write(steam.resolve("DeviceMappings.xml"), "<Root/>", BEFORE);
        watch = watching(target(1, steam, false));
        watch.poll();

        write(mappings, "<Root/>", AFTER);
        watch.poll();

        assertEquals(1, told.get());
    }

    @Test
    void aButtonMapWrittenCreatedOrDeletedIsReported() throws IOException {
        Path steam = install("steam");
        Path lvwap = write(steam.resolve("DeviceButtonMaps/LVWAP.buttonMap"), "<Root/>", BEFORE);
        watch = watching(target(1, steam, false));
        watch.poll();

        write(lvwap, "<Root/>", AFTER);
        watch.poll();
        write(steam.resolve("DeviceButtonMaps/RVWAP.buttonMap"), "<Root/>", BEFORE);
        watch.poll();
        Files.delete(lvwap);
        watch.poll();

        assertEquals(3, told.get());
    }

    /** One poll that finds several files changed - an Apply writes them together - asks for one refresh. */
    @Test
    void severalFilesInOnePollAreOneReport() throws IOException {
        Path steam = install("steam");
        Path mappings = write(steam.resolve("DeviceMappings.xml"), "<Root/>", BEFORE);
        Path lvwap = write(steam.resolve("DeviceButtonMaps/LVWAP.buttonMap"), "<Root/>", BEFORE);
        watch = watching(target(1, steam, false));
        watch.poll();

        write(mappings, "<Root/>", AFTER);
        write(lvwap, "<Root/>", AFTER);
        watch.poll();

        assertEquals(1, told.get());
    }

    @Test
    void nothingChangedIsNotReported() throws IOException {
        Path steam = install("steam");
        write(steam.resolve("DeviceMappings.xml"), "<Root/>", BEFORE);
        watch = watching(target(1, steam, false));
        watch.poll();

        watch.poll();
        watch.poll();

        assertEquals(0, told.get());
    }

    /** Only device files: anything else the folders hold - a preset, a temp file mid-write - is not watched. */
    @Test
    void otherFilesInTheFoldersAreIgnored() throws IOException {
        Path steam = install("steam");
        write(steam.resolve("DeviceMappings.xml"), "<Root/>", BEFORE);
        Path preset = write(steam.resolve("KeyboardMouseOnly.binds"), "<Root/>", BEFORE);
        Path note = write(steam.resolve("DeviceButtonMaps/readme.txt"), "x", BEFORE);
        watch = watching(target(1, steam, false));
        watch.poll();

        write(preset, "<Root/>", AFTER);
        write(note, "x", AFTER);
        watch.poll();

        assertEquals(0, told.get());
    }

    /** Installations are asked on every poll, so one added later is watched without a restart. */
    @Test
    void anInstallationAddedLaterIsWatched() throws IOException {
        Path steam = install("steam");
        Path epic = install("epic");
        Path epicMappings = write(epic.resolve("DeviceMappings.xml"), "<Root/>", BEFORE);
        List<Target> targets = new ArrayList<>(List.of(target(1, steam, false)));
        watch = new DeviceFilesWatch(() -> List.copyOf(targets), told::incrementAndGet, NEVER);
        watch.poll();

        targets.add(target(2, epic, false));
        watch.poll();
        told.set(0);

        write(epicMappings, "<Root/>", AFTER);
        watch.poll();

        assertEquals(1, told.get());
    }

    /** An installation the registry knows is gone is not read - its drive may be the one that stopped answering. */
    @Test
    void anInstallationKnownToBeMissingIsNotWatched() throws IOException {
        Path steam = install("steam");
        Path mappings = write(steam.resolve("DeviceMappings.xml"), "<Root/>", BEFORE);
        watch = watching(target(1, steam, true));
        watch.poll();

        write(mappings, "<Root/>", AFTER);
        watch.poll();

        assertEquals(0, told.get());
    }

    /** A failure reading the installations is contained: the schedule would otherwise stop for good, silently. */
    @Test
    void aFailedPollDoesNotStopTheWatch() throws IOException, InterruptedException {
        Path steam = install("steam");
        Path mappings = write(steam.resolve("DeviceMappings.xml"), "<Root/>", BEFORE);
        AtomicInteger asked = new AtomicInteger();
        CountDownLatch reported = new CountDownLatch(1);
        watch = new DeviceFilesWatch(() -> {
            if (asked.incrementAndGet() == 2) throw new IllegalStateException("database locked");
            return List.of(target(1, steam, false));
        }, reported::countDown, Duration.ofMillis(20));
        watch.start();

        // WHY: after the failing poll, so the first poll's record is from before the write.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (asked.get() < 3) {
            assertTrue(System.nanoTime() < deadline, "the watch stopped polling after a failure");
            Thread.onSpinWait();
        }
        write(mappings, "<Root/>", AFTER);

        assertTrue(reported.await(5, TimeUnit.SECONDS), "the watch kept polling after a failure");
    }

    private DeviceFilesWatch watching(Target target) {
        return new DeviceFilesWatch(() -> List.of(target), told::incrementAndGet, NEVER);
    }

    private static Target target(long id, Path controlSchemes, boolean missing) {
        return new Target(id, "STEAM", controlSchemes, missing);
    }

    private Path install(String name) throws IOException {
        return Files.createDirectories(dir.resolve(name).resolve("ControlSchemes"));
    }

    private static Path write(Path file, String content, Instant modified) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }
}

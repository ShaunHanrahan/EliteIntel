package elite.intel.bindforge.io;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What {@link BindingsWriter#rewriteDeviceReferences(List, DeviceReferenceRewrite, Path)} did across its files.
 * <p>
 * The rewrite is all or nothing: on any status but {@link Status#REWRITTEN} every file is as it was, except those
 * listed in {@link #notRestored()}: a file the game saved again after the rewrite wrote it, which is left as the
 * game wrote it, or a failure while putting a file back, which leaves it rewritten. Either way its pre-rewrite copy
 * is in the backup folder.
 *
 * @param status      how it ended
 * @param references  per file, how many references matched - rewritten on success, left alone otherwise; only
 *                    files with at least one, in the order given
 * @param failedFile  the file the rewrite stopped on, or {@code null} when it did not stop
 * @param notRestored files not put back after a failure: saved by the game since, or the restore failed too
 */
public record DeviceRewriteResult(Status status, Map<Path, Integer> references, Path failedFile, List<Path> notRestored) {

    public enum Status {
        /** Every file holding a reference was rewritten. */
        REWRITTEN,
        /** No file named the device; nothing was written. */
        NO_REFERENCES,
        /** A file could not be read; nothing was written. */
        READ_FAILED,
        /** A file's text could not be rewritten whole - a start tag never closes; nothing was written. */
        UNSUPPORTED_XML,
        /** A file could not be backed up; nothing was written. */
        BACKUP_FAILED,
        /** A file changed between being read and being written - the game saved it - so everything was put back. */
        STALE_FILE,
        /** A write failed, so everything already written was put back. */
        WRITE_FAILED
    }

    public DeviceRewriteResult {
        Objects.requireNonNull(status, "status");
        references = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(references, "references")));
        notRestored = List.copyOf(Objects.requireNonNull(notRestored, "notRestored"));
    }

    /** True when the files are now consistent: rewritten, or there was nothing to rewrite. */
    public boolean succeeded() {
        return status == Status.REWRITTEN || status == Status.NO_REFERENCES;
    }

    /** The number of references across every file. */
    public int totalReferences() {
        return references.values().stream().mapToInt(Integer::intValue).sum();
    }
}

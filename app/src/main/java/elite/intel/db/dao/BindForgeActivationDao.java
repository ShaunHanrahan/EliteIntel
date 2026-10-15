package elite.intel.db.dao;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * Whether BindForge is activated, and its "before BindForge" backup. One row, at {@code id = 1}, created by
 * {@code 12005__bindforge_activation.sql}.
 * <p>
 * Callers go through {@link elite.intel.db.managers.BindForgeActivationManager}, or - to activate in the same
 * transaction that fills the master - {@link elite.intel.db.managers.BindForgeDeviceDraftManager#establish}.
 */
public interface BindForgeActivationDao {

    @SqlQuery("SELECT activated_at IS NOT NULL FROM bindforge_activation WHERE id = 1")
    boolean isActivated();

    /** Activates, keeping the first time it happened. */
    @SqlUpdate("""
            UPDATE bindforge_activation
            SET activated_at = COALESCE(activated_at, CURRENT_TIMESTAMP)
            WHERE id = 1
            """)
    void activate();

    @SqlQuery("SELECT backup_folder FROM bindforge_activation WHERE id = 1")
    String backupFolder();

    @SqlUpdate("UPDATE bindforge_activation SET backup_folder = :folder WHERE id = 1")
    void recordBackupFolder(@Bind("folder") String folder);
}

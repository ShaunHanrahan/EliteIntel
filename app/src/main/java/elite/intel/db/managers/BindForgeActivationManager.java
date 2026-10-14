package elite.intel.db.managers;

import elite.intel.db.dao.BindForgeActivationDao;
import elite.intel.db.util.Database;

/**
 * Whether BindForge is activated, read and written through {@link BindForgeActivationDao} (alias-designer.md,
 * <em>Activation</em>, 2026-10-13).
 * <p>
 * <strong>Activated is its own fact, not "the master has rows".</strong> A user who owns nothing is activated with
 * an empty master. <strong>The one way to activate is {@link BindForgeDeviceDraftManager#establish}</strong>, in the
 * transaction that fills the master - so there is deliberately no {@code activate()} here (review, 2026-10-13).
 */
public class BindForgeActivationManager {

    private static volatile BindForgeActivationManager instance;

    private BindForgeActivationManager() {
        // Private constructor to prevent instantiation
    }

    public static BindForgeActivationManager getInstance() {
        if (instance == null) {
            synchronized (BindForgeActivationManager.class) {
                if (instance == null) {
                    instance = new BindForgeActivationManager();
                }
            }
        }
        return instance;
    }

    public boolean isActivated() {
        return Database.withDao(BindForgeActivationDao.class, BindForgeActivationDao::isActivated);
    }

    /** The "before BindForge" backup's folder, or {@code null} when it has not been taken. */
    public String backupFolder() {
        return Database.withDao(BindForgeActivationDao.class, BindForgeActivationDao::backupFolder);
    }

    public void recordBackupFolder(String folder) {
        Database.withDao(BindForgeActivationDao.class, dao -> {
            dao.recordBackupFolder(folder);
            return Void.TYPE;
        });
    }
}

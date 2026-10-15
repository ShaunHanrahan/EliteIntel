package elite.intel.bindforge.install;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Finds Elite Dangerous installations on Linux: the game runs under Proton, so through Steam.
 * <p>
 * <strong>Several Steam clients can be installed at once</strong> - native, Flatpak and Snap are independent of one
 * another (file-manager.md, <em>Linux</em>) - so every place one can live is read, and each one's library index says
 * where its games went. {@code ~/.steam/steam} is normally a link to {@code ~/.local/share/Steam}; the same client or
 * the same game reached twice is reported once.
 * <p>
 * <strong>Each installation has its own bindings folder</strong>, inside its Proton prefix
 * ({@code compatdata/359320}) in the library that holds the game (input environment, section 7).
 * <p>
 * Epic and Frontier-launcher copies run through Lutris, Heroic or a hand-made Wine prefix, which record nothing a
 * reader can rely on; those reach BindForge through the manual add.
 */
public class LinuxGameInstallationProvider implements GameInstallationProvider {

    /**
     * The bindings folder inside a Proton prefix, below {@code pfx}.
     */
    private static final String PREFIX_BINDINGS =
            "drive_c/users/steamuser/AppData/Local/Frontier Developments/Elite Dangerous/Options/Bindings";

    private final List<Path> steamRoots;

    public LinuxGameInstallationProvider() {
        this(steamRootsUnder(Path.of(System.getProperty("user.home"))));
    }

    /**
     * Test seam: the Steam clients' folders supplied rather than looked up under this user's home.
     */
    LinuxGameInstallationProvider(List<Path> steamRoots) {
        this.steamRoots = List.copyOf(steamRoots);
    }

    @Override
    public List<GameInstallation> findInstallations() {
        // WHY: keyed by real path, so a game reached through two links, or listed by two clients sharing a library,
        // is reported once - as the Windows provider does.
        Map<Path, GameInstallation> found = new LinkedHashMap<>();
        for (Path steamRoot : distinct(steamRoots)) {
            for (Path library : SteamLibraries.holdingTheGame(steamRoot)) {
                Path root = SteamLibraries.gameRootIn(library);
                if (!GameInstallation.looksLikeAnInstall(root)) continue;
                found.putIfAbsent(realPathOf(root), new GameInstallation(Storefront.STEAM, root,
                        GameInstallation.controlSchemesUnder(root), protonBindingsIn(library)));
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * Every place a Steam client keeps its own folder on Linux: native (both spellings), Snap and Flatpak.
     */
    static List<Path> steamRootsUnder(Path home) {
        return List.of(
                home.resolve(".steam/steam"),
                home.resolve(".local/share/Steam"),
                home.resolve("snap/steam/common/.local/share/Steam"),
                home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam"));
    }

    /**
     * The game's own bindings folder, in the Proton prefix of the library that holds it, whether or not it exists.
     */
    static Path protonBindingsIn(Path library) {
        return library.resolve("steamapps").resolve("compatdata").resolve(SteamLibraries.APP_ID).resolve("pfx")
                .resolve(PREFIX_BINDINGS);
    }

    /**
     * The Steam folders, each client once however many links reach it.
     */
    private static List<Path> distinct(List<Path> roots) {
        Set<Path> seen = new LinkedHashSet<>();
        List<Path> distinct = new ArrayList<>();
        for (Path root : roots) {
            if (seen.add(realPathOf(root))) distinct.add(root);
        }
        return distinct;
    }

    private static Path realPathOf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | UncheckedIOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}

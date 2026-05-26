package com.worldreset.checker;

import com.worldreset.WorldResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

/**
 * Checks for WorldResetPlugin updates against the GitHub Releases API on
 * startup, then notifies any operator/admin who joins while a newer version
 * is available.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>On {@link #check()}, a single async Bukkit task hits:<br>
 *       {@code GET https://api.github.com/repos/{owner}/{repo}/releases/latest}<br>
 *       and extracts the {@code tag_name} field (e.g. {@code "v1.3.2"}) without
 *       pulling in any JSON library — just a targeted string search on the
 *       one field we care about.</li>
 *   <li>The version is compared to the running plugin version from
 *       {@code plugin.yml}.  Leading {@code "v"} prefixes are stripped before
 *       comparison so {@code "v1.3.1"} and {@code "1.3.1"} match correctly.</li>
 *   <li>If a newer version is found, the result is cached and an in-game
 *       message is sent (with a 2-second delay so it isn't buried by join
 *       noise) to every player with {@code worldreset.admin} when they join.</li>
 * </ol>
 *
 * <h2>Configuration ({@code config.yml})</h2>
 * <pre>
 * update-checker:
 *   enabled: true
 *   github-repo: "YourUsername/WorldResetPlugin"   # owner/repo
 * </pre>
 *
 * <h2>Threading</h2>
 * The HTTP call is fully async and never blocks the main thread.
 * The cached result fields ({@link #latestVersion}, {@link #upToDate}) are
 * only written once from the async task before any reads can occur on the main
 * thread, so no explicit synchronisation is required.
 */
public class UpdateChecker implements Listener {

    // Permission required to receive the in-game update nag.
    private static final String NOTIFY_PERM = "worldreset.admin";

    // HTTP connection / read timeouts in milliseconds.
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 5_000;

    // Delay (in ticks) before showing the join notification, so it isn't
    // lost in the initial burst of login messages.
    private static final long JOIN_NOTIFY_DELAY_TICKS = 40L; // 2 seconds

    private final WorldResetPlugin plugin;
    private final Logger           log;

    /** Latest version tag from GitHub, e.g. "v1.3.2". Null until the check completes. */
    private volatile String  latestVersion = null;

    /** False if a newer version was found; true otherwise (including before check runs). */
    private volatile boolean upToDate      = true;

    public UpdateChecker(WorldResetPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Fire the async version-check.  Call once from {@code onEnable()} after
     * the config has been loaded.  Safe to call again after a reload.
     */
    public void check() {
        String repo = plugin.getConfigManager().getUpdateCheckerRepo();
        if (repo == null || repo.isEmpty() || repo.equals("YourUsername/WorldResetPlugin")) {
            log.warning("[UpdateChecker] update-checker.github-repo is not configured. "
                    + "Set it to 'owner/repo' in config.yml to enable version checks.");
            return;
        }

        String apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String current        = plugin.getDescription().getVersion();
                String latest         = fetchTagName(apiUrl);
                if (latest == null || latest.isEmpty()) return;

                latestVersion         = latest;
                String normLatest     = latest.startsWith("v")   ? latest.substring(1)   : latest;
                String normCurrent    = current.startsWith("v")  ? current.substring(1)  : current;

                if (normLatest.equals(normCurrent)) {
                    upToDate = true;
                    log.info("[UpdateChecker] Plugin is up to date (v" + current + ").");
                } else {
                    upToDate = false;
                    log.warning("[UpdateChecker] *** Update available: " + latest
                            + " (running v" + current + ") ***");
                    log.warning("[UpdateChecker] Download: https://github.com/" + repo + "/releases/latest");
                }

            } catch (Exception e) {
                // Never crash the server over a version check.
                log.warning("[UpdateChecker] Could not reach GitHub: " + e.getMessage());
            }
        });
    }

    /** Whether the running version is current (or the check has not completed yet). */
    public boolean isUpToDate() {
        return upToDate;
    }

    /** Latest version string as returned by GitHub, or null if the check has not completed. */
    public String getLatestVersion() {
        return latestVersion;
    }

    // ── Bukkit listener ────────────────────────────────────────────────────

    /**
     * Notify ops/admins of the available update when they join, if applicable.
     * Fires with a short delay so the message isn't buried in login noise.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (upToDate || latestVersion == null) return;

        Player player = event.getPlayer();
        if (!player.hasPermission(NOTIFY_PERM)) return;

        String current = plugin.getDescription().getVersion();
        String repo    = plugin.getConfigManager().getUpdateCheckerRepo();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage("§8[§6WorldReset§8] §eUpdate available: §a" + latestVersion
                    + " §7(you have §cv" + current + "§7)");
            player.sendMessage("§8[§6WorldReset§8] §7» §bhttps://github.com/" + repo + "/releases/latest");
        }, JOIN_NOTIFY_DELAY_TICKS);
    }

    // ── HTTP / parsing ─────────────────────────────────────────────────────

    /**
     * Fetch the {@code tag_name} from the GitHub Releases API response.
     *
     * <p>We intentionally avoid pulling in a JSON library.  The GitHub API
     * response for {@code /releases/latest} is a large JSON object, but
     * {@code tag_name} always appears as a simple quoted string near the top.
     * A targeted string search is reliable and keeps the plugin dependency-free.</p>
     *
     * @param apiUrl  full GitHub API URL for the latest release
     * @return the tag name string (e.g. {@code "v1.3.2"}), or {@code null} on failure
     * @throws Exception on any network or I/O error (caller logs and swallows)
     */
    private String fetchTagName(String apiUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept",     "application/vnd.github.v3+json");
        conn.setRequestProperty("User-Agent", "WorldResetPlugin-UpdateChecker");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);

        int status = conn.getResponseCode();
        if (status == 404) {
            log.warning("[UpdateChecker] Repository not found (HTTP 404). "
                    + "Check update-checker.github-repo in config.yml.");
            return null;
        }
        if (status != 200) {
            log.warning("[UpdateChecker] GitHub API returned HTTP " + status + " — skipping check.");
            return null;
        }

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
                // Stop reading once we've seen tag_name — no need to download
                // the full (potentially large) release JSON.
                if (body.indexOf("\"tag_name\"") >= 0) break;
            }
        } finally {
            conn.disconnect();
        }

        return extractJsonString(body.toString(), "tag_name");
    }

    /**
     * Pull the string value of {@code key} from a flat JSON snippet without
     * a library.  Handles the form {@code "key":"value"} (with optional spaces).
     *
     * @param json  JSON text to search
     * @param key   the key whose value to extract
     * @return the extracted value, or {@code null} if not found
     */
    private static String extractJsonString(String json, String key) {
        // Match: "key" : "value"  (spaces around : are optional)
        String marker = "\"" + key + "\"";
        int keyIdx = json.indexOf(marker);
        if (keyIdx < 0) return null;

        int colon = json.indexOf(':', keyIdx + marker.length());
        if (colon < 0) return null;

        int open = json.indexOf('"', colon + 1);
        if (open < 0) return null;

        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;

        return json.substring(open + 1, close);
    }
}
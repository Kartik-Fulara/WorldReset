package com.worldreset.managers;

import com.worldreset.WorldResetPlugin;
import org.bukkit.Bukkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Owns the read->cache->patch lifecycle for {@code server.properties}.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>{@link #load()} reads the actual file from disk into an ordered
 *       in-memory map ({@code liveProperties}) on plugin startup.  The map
 *       preserves the original key order so round-tripping the file does not
 *       scramble the layout.</li>
 *   <li>The cache is the authoritative source for the current on-disk state.
 *       All in-game list/status commands read from it.</li>
 *   <li>{@link #applyPatches(Map)} merges the plugin's patch list on top of
 *       the cached values and writes the result back to disk.  It never
 *       discards keys that are not in the patch list.</li>
 *   <li>{@link #reload()} re-reads the file without restarting the plugin,
 *       useful after a manual edit or after a previous patch.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@link #load()} and {@link #reload()} are called on the main thread during
 * plugin enable.  {@link #applyPatches(Map)} is called from the reset sequence
 * just before {@code spigot().restart()} — also on the main thread.
 * No concurrent access is expected; no synchronisation is needed.
 */
public class ServerPropertiesManager {

    private final WorldResetPlugin plugin;
    private final Logger           log;

    /** Live snapshot of every key→value pair currently in server.properties. */
    private final LinkedHashMap<String, String> liveProperties = new LinkedHashMap<>();

    /** Path to server.properties, resolved once on construction. */
    private final File propsFile;

    /** Whether the file was successfully read at least once. */
    private boolean loaded = false;

    public ServerPropertiesManager(WorldResetPlugin plugin) {
        this.plugin    = plugin;
        this.log       = plugin.getLogger();
        this.propsFile = resolveFile();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Read {@code server.properties} from disk and populate the cache.
     * Called once during {@code onEnable()}.  Safe to call again after a
     * manual file edit — it replaces the cache completely.
     */
    public void load() {
        liveProperties.clear();
        loaded = false;

        if (!propsFile.isFile()) {
            log.warning("[ServerProps] server.properties not found at: "
                    + propsFile.getAbsolutePath()
                    + " — serverprops commands will be unavailable.");
            return;
        }

        try {
            readIntoMap(propsFile, liveProperties);
            loaded = true;
            log.info("[ServerProps] Loaded " + liveProperties.size()
                    + " properties from " + propsFile.getName() + ".");
        } catch (IOException e) {
            log.severe("[ServerProps] Failed to read server.properties: " + e.getMessage());
        }
    }

    /**
     * Re-read the file from disk, discarding the current cache.
     * Use after a manual edit or after {@link #applyPatches} has been called.
     */
    public void reload() {
        log.info("[ServerProps] Reloading server.properties cache…");
        load();
    }

    /**
     * Whether the file was successfully read on the last {@link #load()} call.
     * When false, the cache is empty and patch/write operations are skipped.
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Returns an unmodifiable view of the full live server.properties cache.
     * Keys appear in the order they were found in the file.
     */
    public Map<String, String> getLiveProperties() {
        return Collections.unmodifiableMap(liveProperties);
    }

    /**
     * Returns the current value of a single key, or {@code null} if absent.
     */
    public String getLiveValue(String key) {
        return liveProperties.get(key);
    }

    /**
     * Returns all keys currently in the file — useful for tab-completion so
     * operators only see keys that actually exist in their server.properties.
     */
    public Set<String> getLiveKeys() {
        return Collections.unmodifiableSet(liveProperties.keySet());
    }

    /**
     * Merge {@code patches} on top of the cached properties and write the
     * result back to {@code server.properties}.
     *
     * <p>Keys not present in {@code patches} are written exactly as they were
     * read — no data is lost.  After a successful write the in-memory cache is
     * updated to reflect the new state.</p>
     *
     * @param patches  key→value pairs to set (from {@code ConfigManager.getServerPropertiesValues()})
     * @return {@code true} if the file was written without error
     */
    public boolean applyPatches(Map<String, String> patches) {
        if (!loaded) {
            log.severe("[ServerProps] Cannot patch — server.properties was not loaded successfully.");
            return false;
        }
        if (patches == null || patches.isEmpty()) {
            log.info("[ServerProps] No patches configured — server.properties left unchanged.");
            return true;
        }

        // Build a merged map:  start from the live snapshot, overlay patches
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(liveProperties);
        patches.forEach((k, v) -> {
            String old = merged.put(k, v);
            if (old == null) {
                log.info("[ServerProps] + " + k + " = " + v + "  (new key)");
            } else if (!old.equals(v)) {
                log.info("[ServerProps] ~ " + k + ": " + old + "  →  " + v);
            } else {
                log.info("[ServerProps] = " + k + " = " + v + "  (unchanged)");
            }
        });

        // Write the merged map back to disk preserving key order
        try {
            writeFromMap(propsFile, merged, plugin.getDescription().getVersion());
            // Update the cache to match what is now on disk
            liveProperties.clear();
            liveProperties.putAll(merged);
            log.info("[ServerProps] Patched " + patches.size()
                    + " key(s) in server.properties.");
            return true;
        } catch (IOException e) {
            log.severe("[ServerProps] Write failed: " + e.getMessage());
            return false;
        }
    }

    // ── File I/O ───────────────────────────────────────────────────────────

    /**
     * Parse a {@code .properties} file into {@code target} while preserving
     * key order and handling comment lines / blank lines gracefully.
     *
     * <p>We intentionally do NOT use {@link java.util.Properties#load} here
     * because that class uses a {@link java.util.Hashtable} internally which
     * does not preserve insertion order.  Minecraft's server.properties file
     * uses the same {@code key=value} format so we can parse it ourselves.</p>
     */
    private static void readIntoMap(File file, LinkedHashMap<String, String> target)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Skip blank lines and comment lines (# or !)
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) continue; // malformed — skip
                String key   = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                target.put(key, value);
            }
        }
    }

    /**
     * Write {@code source} back to {@code file} in {@code key=value} format,
     * with a plugin-stamped header comment and one entry per line.
     *
     * <p>Uses UTF-8 explicitly so Mojang's unicode escaping ({@code \\uXXXX})
     * is not applied — Minecraft 1.13+ reads server.properties as UTF-8.</p>
     */
    private static void writeFromMap(File file, LinkedHashMap<String, String> source, String pluginVersion)
            throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write("# Minecraft server properties");
            writer.newLine();
            writer.write("# Patched by WorldResetPlugin v" + pluginVersion + " on "
                    + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            .format(new Date()));
            writer.newLine();
            for (Map.Entry<String, String> e : source.entrySet()) {
                writer.write(e.getKey() + "=" + e.getValue());
                writer.newLine();
            }
        }
    }

    // ── Path resolution ────────────────────────────────────────────────────

    /**
     * Resolve the path to {@code server.properties} using the same priority
     * as {@code ResetManager.serverRoot()} so both classes always agree on
     * where the server root is.
     */
    private File resolveFile() {
        File root;
        try {
            root = new File(".").getCanonicalFile();
        } catch (IOException e) {
            File wc = Bukkit.getWorldContainer();
            root = (wc != null && wc.getParentFile() != null)
                    ? wc.getParentFile()
                    : plugin.getDataFolder().getAbsoluteFile()
                            .getParentFile()
                            .getParentFile();
        }
        return new File(root, "server.properties");
    }
}
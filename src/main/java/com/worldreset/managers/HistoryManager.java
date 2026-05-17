package com.worldreset.managers;

import com.worldreset.WorldResetPlugin;
import org.bukkit.command.CommandSender;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * Persists a human-readable reset history log to
 * {@code plugins/WorldResetPlugin/reset-history.log}.
 *
 * <h2>Log format</h2>
 * <pre>
 * [2025-05-16 04:00:12] RESET     | Triggered by: Schedule | Mode: restart | Worlds: world, world_nether, world_the_end | Duration: 47s
 * [2025-05-16 04:00:05] CANCELLED | By: Steve | Seconds remaining: 8
 * </pre>
 *
 * <h2>In-game command</h2>
 * {@code /worldreset history [n]} — prints the last n lines (default 10).
 */
public class HistoryManager {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final int    DEFAULT_LINES = 10;

    private final WorldResetPlugin plugin;
    private final Logger           log;
    private final File             logFile;

    public HistoryManager(WorldResetPlugin plugin) {
        this.plugin  = plugin;
        this.log     = plugin.getLogger();
        this.logFile = new File(plugin.getDataFolder(), "reset-history.log");
        plugin.getDataFolder().mkdirs();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Append a reset-started entry to the history log.
     * Called at the moment a reset is confirmed (countdown begins or instant fire).
     *
     * @param triggeredBy  player name, "Schedule", or "Vote"
     * @param worlds       list of worlds that will be reset
     */
    public void logResetStart(String triggeredBy, List<String> worlds) {
        String line = String.format("[%s] STARTED  | Triggered by: %s | Worlds: %s",
                now(), triggeredBy, String.join(", ", worlds));
        append(line);
    }

    /**
     * Append a completed-reset entry to the history log.
     *
     * @param triggeredBy   player name, "Schedule", or "Vote"
     * @param mode          "restart" or "live-regeneration"
     * @param worlds        list of world names that were reset
     * @param durationSecs  seconds from startReset() to completion
     */
    public void logReset(String triggeredBy, String mode, List<String> worlds, long durationSecs) {
        String line = String.format("[%s] RESET | Triggered by: %s | Mode: %s | Worlds: %s | Duration: %ds",
                now(), triggeredBy, mode, String.join(", ", worlds), durationSecs);
        append(line);
    }

    /**
     * Append a cancelled-reset entry to the history log.
     *
     * @param cancelledBy      player name or "Console"
     * @param secondsRemaining seconds left on the countdown when cancelled
     */
    public void logCancelled(String cancelledBy, int secondsRemaining) {
        String line = String.format("[%s] CANCELLED | By: %s | Seconds remaining: %d",
                now(), cancelledBy, secondsRemaining);
        append(line);
    }

    /**
     * Print the last {@code n} history lines to {@code sender}.
     * Prints up to {@link #DEFAULT_LINES} lines when n ≤ 0.
     */
    public void showHistory(CommandSender sender, int n) {
        int count = (n <= 0) ? DEFAULT_LINES : n;

        if (!logFile.exists()) {
            sender.sendMessage("§7[History] No reset history found yet.");
            return;
        }

        List<String> lines = readLastLines(count);
        if (lines.isEmpty()) {
            sender.sendMessage("§7[History] Log file is empty.");
            return;
        }

        sender.sendMessage("§8§m----------§r §6§lReset History (last " + lines.size() + ") §8§m----------§r");
        for (String l : lines) {
            // Colour the entry type for readability
            if (l.contains("] RESET ")) {
                sender.sendMessage("§a" + l);
            } else if (l.contains("] STARTED ")) {
                sender.sendMessage("§b" + l);           // cyan = started
            } else if (l.contains("] CANCELLED ")) {
                sender.sendMessage("§e" + l);
            } else {
                sender.sendMessage("§7" + l);
            }
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private void append(String line) {
        try (PrintWriter writer = new PrintWriter(
                new BufferedWriter(new FileWriter(logFile, true)))) {
            writer.println(line);
        } catch (IOException e) {
            log.warning("[History] Failed to write to reset-history.log: " + e.getMessage());
        }
    }

    /**
     * Read the last {@code n} non-empty lines from the log file efficiently
     * by scanning from the end of the file.
     */
    private List<String> readLastLines(int n) {
        List<String> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            long fileLen = raf.length();
            if (fileLen == 0) return result;

            long pos = fileLen - 1;
            StringBuilder sb = new StringBuilder();

            while (pos >= 0 && result.size() < n) {
                raf.seek(pos);
                char c = (char) raf.read();
                if (c == '\n') {
                    String line = sb.reverse().toString().trim();
                    if (!line.isEmpty()) result.add(0, line);
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
                pos--;
            }
            // Handle the very first line (no leading newline)
            if (sb.length() > 0 && result.size() < n) {
                String line = sb.reverse().toString().trim();
                if (!line.isEmpty()) result.add(0, line);
            }
        } catch (IOException e) {
            log.warning("[History] Failed to read reset-history.log: " + e.getMessage());
        }
        return result;
    }

    private static String now() {
        return new SimpleDateFormat(DATE_FORMAT).format(new Date());
    }
}
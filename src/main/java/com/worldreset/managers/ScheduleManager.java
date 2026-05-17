package com.worldreset.managers;

import com.worldreset.WorldResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.logging.Logger;

/**
 * Drives the optional auto-reset schedule.
 *
 * Two modes are supported:
 * <ul>
 *   <li><b>interval</b> — fires a reset every N seconds (configured via
 *       {@code schedule.interval-seconds}).  A safety floor applies —
 *       see {@code schedule.min-interval-seconds}.</li>
 *   <li><b>daily</b>    — fires once per day at a wall-clock time (HH:MM)
 *       in the timezone set by {@code schedule.timezone} (default UTC).
 *       Configured via {@code schedule.daily-time}.</li>
 * </ul>
 *
 * The schedule is restarted automatically when the plugin reloads
 * ({@link #restart()}) and stopped cleanly on plugin disable ({@link #stop()}).
 *
 * In-game commands:
 * <pre>
 *   /worldreset schedule status   — show current schedule configuration
 *   /worldreset schedule enable   — enable the schedule (saves to config)
 *   /worldreset schedule disable  — disable the schedule (saves to config)
 * </pre>
 */
public class ScheduleManager {

    private static final long DAILY_CHECK_TICKS = 20L; // check once per second

    private final WorldResetPlugin plugin;
    private final Logger           log;
    private BukkitTask             task = null;

    // Tracks whether we have already fired for the current daily-minute window
    // so we don't trigger multiple times in the same minute.
    private int lastDailyMinuteFired = -1;

    // ── Next-fire tracking (improvement #5) ───────────────────────────────
    /** Epoch-ms when the last fireTrigger() call occurred (interval mode). */
    private long lastFireMs       = 0L;
    /** Resolved interval in seconds (set in startInterval). */
    private int  activeIntervalSecs = 0;
    /** Resolved target hour for daily mode. */
    private int  dailyTargetHour  = -1;
    /** Resolved target minute for daily mode. */
    private int  dailyTargetMin   = -1;
    /** Resolved ZoneId for daily mode. */
    private ZoneId dailyZone      = ZoneId.of("UTC");
    /** Current schedule mode: "interval", "daily", or "" when stopped. */
    private String activeMode     = "";

    public ScheduleManager(WorldResetPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Start the schedule task (if enabled in config). */
    public void start() {
        stop(); // cancel any existing task first

        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isScheduleEnabled()) return;

        String mode = cfg.getScheduleMode().toLowerCase();
        activeMode = mode;

        switch (mode) {
            case "interval":
                startInterval(cfg);
                break;
            case "daily":
                startDaily(cfg);
                break;
            default:
                log.warning("Unknown schedule mode '" + mode
                        + "'. Valid values: interval, daily. Schedule disabled.");
                activeMode = "";
        }
    }

    /** Stop and discard the current schedule task. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
            lastDailyMinuteFired = -1;
            activeMode = "";
        }
    }

    /**
     * Stop the existing task, reload config values, and restart the schedule.
     * Call this after a {@code /worldreset reload}.
     */
    public void restart() {
        stop();
        start();
    }

    /** Whether a schedule task is currently running. */
    public boolean isRunning() {
        return task != null;
    }

    // ── Next-fire description (improvement #5) ────────────────────────────

    /**
     * Human-readable description of when the next reset will fire.
     * Returns an empty string when the schedule is not running.
     */
    public String getNextFireDescription() {
        if (task == null) return "";

        if ("interval".equals(activeMode)) {
            if (lastFireMs == 0L) {
                return "in ~" + formatSecs(activeIntervalSecs) + " (first cycle)";
            }
            long nextMs    = lastFireMs + (long) activeIntervalSecs * 1000L;
            long remaining = (nextMs - System.currentTimeMillis()) / 1000L;
            if (remaining <= 0) return "imminent";
            return "in " + formatSecs((int) remaining);
        }

        if ("daily".equals(activeMode) && dailyTargetHour >= 0) {
            LocalTime now    = LocalTime.now(dailyZone);
            LocalTime target = LocalTime.of(dailyTargetHour, dailyTargetMin);
            String timeStr   = String.format("%02d:%02d", dailyTargetHour, dailyTargetMin);
            return now.isBefore(target)
                    ? "today at " + timeStr + " (" + dailyZone.getId() + ")"
                    : "tomorrow at " + timeStr + " (" + dailyZone.getId() + ")";
        }

        return "(unknown)";
    }

    // ── Mode implementations ───────────────────────────────────────────────

    private void startInterval(ConfigManager cfg) {
        int requestedSecs = cfg.getScheduleIntervalSecs();
        int minSecs       = cfg.getScheduleMinIntervalSecs();  // Fix 3: configurable floor
        int actualSecs    = Math.max(minSecs, requestedSecs);
        if (actualSecs != requestedSecs) {
            log.warning("Schedule interval " + requestedSecs + "s is below the minimum ("
                    + minSecs + "s). Using " + actualSecs + "s instead.");
        }

        activeIntervalSecs = actualSecs;
        long periodTicks   = actualSecs * 20L;

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::fireTrigger, periodTicks, periodTicks);
        log.info("Schedule: auto-reset every " + actualSecs + "s (interval mode).");
    }

    private void startDaily(ConfigManager cfg) {
        String timeStr = cfg.getScheduleDailyTime(); // expected "HH:MM"
        if (!timeStr.matches("\\d{1,2}:\\d{2}")) {
            log.severe("Invalid schedule.daily-time '" + timeStr
                    + "'. Expected format: HH:MM  (e.g. 04:00). Schedule disabled.");
            return;
        }

        // Fix 4: validate and resolve the configured timezone
        String zoneStr = cfg.getScheduleTimezone();
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneStr);
        } catch (DateTimeException e) {
            log.severe("Invalid schedule.timezone '" + zoneStr
                    + "' — falling back to UTC. Error: " + e.getMessage());
            zone = ZoneId.of("UTC");
        }
        dailyZone = zone;

        String[] initParts = timeStr.split(":");
        dailyTargetHour = Integer.parseInt(initParts[0]);
        dailyTargetMin  = Integer.parseInt(initParts[1]);

        final ZoneId resolvedZone = zone;

        // Check every second
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ConfigManager liveCfg = plugin.getConfigManager();
            String current = liveCfg.getScheduleDailyTime();
            String[] parts = current.split(":");
            if (parts.length != 2) return;

            try {
                int targetHour   = Integer.parseInt(parts[0]);
                int targetMinute = Integer.parseInt(parts[1]);

                // Keep cached values in sync in case config was changed live
                dailyTargetHour = targetHour;
                dailyTargetMin  = targetMinute;

                LocalTime now = LocalTime.now(resolvedZone); // Fix 4: use configured zone

                // Fire once per matching minute window
                if (now.getHour() == targetHour && now.getMinute() == targetMinute) {
                    int minuteKey = targetHour * 60 + targetMinute;
                    if (lastDailyMinuteFired != minuteKey) {
                        lastDailyMinuteFired = minuteKey;
                        fireTrigger();
                    }
                } else {
                    // Reset the guard once we leave the fire window
                    int minuteKey = targetHour * 60 + targetMinute;
                    if (lastDailyMinuteFired == minuteKey) {
                        lastDailyMinuteFired = -1;
                    }
                }
            } catch (NumberFormatException e) {
                log.warning("Schedule daily-time parse error: " + current);
            }
        }, DAILY_CHECK_TICKS, DAILY_CHECK_TICKS);

        log.info("Schedule: auto-reset daily at " + timeStr
                + " " + zone.getId() + " (daily mode).");
    }

    // ── Internal trigger ───────────────────────────────────────────────────

    private void fireTrigger() {
        ResetManager rm = plugin.getResetManager();
        if (rm.isResetPending()) {
            log.info("Schedule: reset due but one is already pending — skipping this cycle.");
            return;
        }
        lastFireMs = System.currentTimeMillis(); // track for next-fire display
        log.info("Schedule: triggering automatic reset.");
        rm.startReset("Schedule", false);
    }

    // ── Formatting helper ──────────────────────────────────────────────────

    private static String formatSecs(int totalSecs) {
        if (totalSecs <= 0) return "0s";
        int h = totalSecs / 3600;
        int m = (totalSecs % 3600) / 60;
        int s = totalSecs % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
package com.worldreset.managers;

import com.worldreset.api.IRegionManager;
import com.worldreset.WorldResetPlugin;
import com.worldreset.api.WorldResetUtil;
import com.worldreset.api.events.*;
import com.worldreset.utils.SecurityUtil;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipInputStream;

/**
 * Handles the full reset lifecycle:
 *   1.  Countdown — titles + chat broadcasts
 *   2.  Kick all players
 *   3.  Enable whitelist (if configured)
 *   4.  Run pre-reset console commands
 *   5.  Unload worlds + clear Paper world registry  (main thread)
 *   6.  [ASYNC] Backup world folders (optional)
 *   7.  [ASYNC] Delete world folders (NIO, respecting preserve list)
 *   8.  [ASYNC] Delete extra paths
 *   9.  [ASYNC] POST Discord webhook notification
 *   10. [MAIN]  Regenerate worlds OR trigger Spigot restart
 *   11. [MAIN]  Apply gamerules + difficulty (type-safe GameRule<T> API)
 *   12. [MAIN]  Apply world border + spawn
 *   13. [MAIN]  Clear player data (in-memory + disk)
 *   14. [MAIN]  Write server.properties (restart mode only)
 *   15. [MAIN]  Disable whitelist
 *   16. [MAIN]  Run post-reset console commands
 *   17. [MAIN]  Broadcast reset-complete message
 *
 * Bug-fix notes (v1.1.0)
 * ──────────────────────
 *  FIX 1 — Async deletion
 *    NIO file deletion (including deleteWithRetry) now runs on an async Bukkit
 *    worker task. createWorld() and applyGamerulesAndDifficulty() are scheduled
 *    back on the main thread via Bukkit.getScheduler().runTask().
 *    Controlled by async.delete-async in config (default true).
 *
 *  FIX 2 — Paper world registry via API
 *    clearPaperWorldRegistry() reflection hack removed.  Bukkit.unloadWorld()
 *    already updates the internal registry.  save=false is passed since the
 *    world folder is deleted immediately after unload.
 *
 *  FIX 3 — Type-safe gamerule application
 *    Deprecated World.setGameRuleValue(String, String) replaced with the
 *    type-safe World.setGameRule(GameRule<T>, T).  Boolean and Integer gamerule
 *    types are handled; unknown names and bad values produce a clear log warning.
 *
 *  FIX 4 — Reliable serverRoot()
 *    Now uses new File(".").getCanonicalFile() (the JVM working directory) as the
 *    primary method, with Bukkit.getWorldContainer().getParentFile() as fallback.
 *    The old getDataFolder().getParentFile().getParentFile() was fragile.
 *
 *  FIX 5 — resetPending guard in restart path
 *    resetPending is set to false in a finally block around spigot().restart()
 *    so it cannot be stuck as true if the restart call throws an exception.
 *
 *  FIX 6 — (plugin.yml) api-version updated to 1.20 — see plugin.yml.
 *
 *  FIX 7 — Confirmation step — implemented in ResetCommand.java.
 *
 *  FIX 8 — Reload guard — implemented in ResetCommand.handleReload().
 */
public class ResetManager {

    private final WorldResetPlugin plugin;
    private final Logger           log;

    private BukkitTask countdownTask = null;
    private boolean    resetPending  = false;
    private int        secondsLeft   = 0;

    /** Epoch-ms timestamp of the last completed reset (for cooldown enforcement). */
    private long lastResetCompleteMs = 0L;

    /** Tracks who triggered the current reset (for history logging). */
    private String  currentInitiator = "Unknown";
    /** Epoch-ms timestamp when the current reset was started (for duration tracking). */
    private long    resetStartMs     = 0L;

    /**
     * Names of players who voted yes in the vote that triggered this reset.
     * Empty for schedule- or player-triggered resets.
     * Populated by VoteManager just before calling startReset().
     * Read-back via plugin.getVoteManager().getLastVoterNames() in buildDiscordMessage().
     */
    // (voter data is owned by VoteManager — see getLastVoterNames())

    // Retry config for deleteWithRetry
    private static final int  DELETE_RETRIES  = 5;
    private static final long DELETE_RETRY_MS = 200;

    public ResetManager(WorldResetPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Handles the full reset initiation flow (confirmation logic + starting).
     * Used by both commands and GUI.
     *
     * @param player the player initiating the reset (can be null for console)
     * @param forceNow if true, skips countdown if confirmed
     * @param confirmed if true, skips the confirmation safety check
     */
    public void handleResetInitiation(org.bukkit.command.CommandSender player, boolean forceNow, boolean confirmed) {
        if (isResetPending()) {
            player.sendMessage("§c[WorldReset] A reset is already in progress.");
            return;
        }

        String senderKey = player instanceof Player
                ? ((Player) player).getUniqueId().toString()
                : "CONSOLE";

        Map<String, Long> pendingConfirm = plugin.getResetCommand().pendingConfirm;
        long CONFIRM_EXPIRE_MS = com.worldreset.commands.ResetCommand.CONFIRM_EXPIRE_MS;

        if (confirmed) {
            // Validate a pending confirmation actually exists and hasn't expired.
            Long prev  = pendingConfirm.get(senderKey);
            long nowMs = System.currentTimeMillis();
            if (prev == null || nowMs - prev > CONFIRM_EXPIRE_MS) {
                player.sendMessage("§c[WorldReset] No pending confirmation — start reset first, then confirm within 30s.");
                pendingConfirm.remove(senderKey);
                return;
            }
            pendingConfirm.remove(senderKey);
        } else {
            long nowMs = System.currentTimeMillis();
            Long prev  = pendingConfirm.get(senderKey);

            if (prev == null || nowMs - prev > CONFIRM_EXPIRE_MS) {
                if (prev != null && nowMs - prev > CONFIRM_EXPIRE_MS) {
                    player.sendMessage("§c[WorldReset] Your confirmation expired. Try again.");
                }
                pendingConfirm.put(senderKey, nowMs);
                
                player.sendMessage("");
                player.sendMessage("§c§l⚠ WARNING: §eThis will permanently delete and regenerate worlds!");
                player.sendMessage("§7Confirm by clicking §a[CONFIRM] §7or typing §f/wr start --confirm");
                
                if (player instanceof Player) {
                    net.md_5.bungee.api.chat.TextComponent confirmBtn = new net.md_5.bungee.api.chat.TextComponent("§a§l[CONFIRM RESET]");
                    confirmBtn.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                            net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, 
                            forceNow ? "/worldreset start --now --confirm" : "/worldreset start --confirm"
                    ));
                    confirmBtn.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                            net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                            new net.md_5.bungee.api.chat.ComponentBuilder("§7Click to initiate the reset process.").create()
                    ));
                    ((Player) player).spigot().sendMessage(confirmBtn);
                }
                player.sendMessage("");
                return;
            }
            pendingConfirm.remove(senderKey);
        }

        // Logic reached here means it's confirmed
        String initiator = player instanceof Player ? player.getName() : "Console";
        startReset(initiator, forceNow);

        if (forceNow) {
            player.sendMessage("§a[WorldReset] Reset triggered immediately.");
        } else {
            int secs = plugin.getConfigManager().getCountdown();
            player.sendMessage("§a[WorldReset] Reset countdown started (" + secs + "s).");
        }
    }

    /** Returns {@code true} if a reset countdown or execution is currently in progress. Main thread. */
    public boolean isResetPending() { return resetPending; }

    /** Returns the number of countdown seconds remaining, or 0 when no reset is pending. Main thread. */
    public int     getSecondsLeft() { return secondsLeft;  }

    /**
     * Begin a reset. Fires a countdown unless {@code skipCountdown} is true.
     *
     * @param initiatorName name shown in the start broadcast
     * @param skipCountdown if true the reset fires immediately
     */
    public void startReset(String initiatorName, boolean skipCountdown) {
        if (resetPending) return;

        // ── Cooldown check ─────────────────────────────────────────────────
        ConfigManager cfg = plugin.getConfigManager();
        int cooldownSecs = cfg.getCooldownSeconds();
        if (cooldownSecs > 0 && lastResetCompleteMs > 0) {
            long elapsedMs  = System.currentTimeMillis() - lastResetCompleteMs;
            long remaining  = cooldownSecs - (elapsedMs / 1000);
            if (remaining > 0) {
                // If the initiator is a named player with bypass perm, skip cooldown
                org.bukkit.entity.Player initiatorPlayer =
                        org.bukkit.Bukkit.getPlayerExact(initiatorName);
                boolean hasBypass = initiatorPlayer != null
                        && initiatorPlayer.hasPermission("worldreset.bypass-cooldown");
                if (!hasBypass) {
                    if (initiatorPlayer != null) {
                        initiatorPlayer.sendMessage(
                                "§c[RESET] Reset on cooldown. Try again in " + remaining + "s.");
                    } else {
                        log.info("Reset blocked by cooldown. Try again in " + remaining + "s.");
                    }
                    return;
                }
            }
        }

        resetPending      = true;
        currentInitiator  = initiatorName;
        resetStartMs      = System.currentTimeMillis();

        // History: record that a reset has been started
        plugin.getHistoryManager().logResetStart(initiatorName, cfg.getWorldsToReset());

        Bukkit.broadcastMessage(color(cfg.getMsgCountdownStart()
                .replace("{player}", initiatorName)));

        if (skipCountdown || cfg.getCountdown() <= 0) {
            secondsLeft = 0;
            executeReset();
        } else {
            secondsLeft = cfg.getCountdown();
            scheduleCountdown();
        }
    }

    /** Abort a pending countdown. Has no effect if no reset is pending. */
    public void cancelReset() {
        cancelReset("Unknown");
    }

    /**
     * Abort a pending countdown, recording who cancelled it in the history log.
     *
     * @param cancelledBy player name or "Console"
     */
    public void cancelReset(String cancelledBy) {
        if (!resetPending) return;
        int remaining = secondsLeft;
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        resetPending = false;
        secondsLeft  = 0;
        Bukkit.broadcastMessage(color(plugin.getConfigManager().getMsgResetCancelled()));
        // History log
        plugin.getHistoryManager().logCancelled(cancelledBy, remaining);
    }

    // ── Countdown ──────────────────────────────────────────────────────────

    private void scheduleCountdown() {
        broadcastCountdown();
        sendTitleToAll();

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            secondsLeft--;

            if (secondsLeft <= 0) {
                countdownTask.cancel();
                countdownTask = null;
                executeReset();
                return;
            }

            ConfigManager cfg      = plugin.getConfigManager();
            int           interval = cfg.getBroadcastInterval();

            if (secondsLeft <= 5 || secondsLeft % interval == 0) {
                broadcastCountdown();
            }
            if (secondsLeft <= 10) {
                sendTitleToAll();
            }

            // ── Feature 5: ActionBar countdown ────────────────────────────
            if (cfg.isActionbarCountdown() && secondsLeft <= cfg.getActionbarThreshold()) {
                String actionBarMsg = color("§c" + secondsLeft + "s §euntil world reset!");
                TextComponent component = new TextComponent(actionBarMsg);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, component);
                }
            }

            // ── Feature 6: Discord countdown milestones ───────────────────
            List<Integer> milestones = cfg.getDiscordCountdownMilestones();
            if (milestones.contains(secondsLeft)) {
                String webhookUrl = cfg.getDiscordWebhookUrl();
                if (webhookUrl != null && !webhookUrl.isEmpty()) {
                    String milestoneMsg = cfg.getDiscordMilestoneMessage()
                            .replace("{seconds}", String.valueOf(secondsLeft));
                    final String finalUrl = webhookUrl;
                    final String finalMsg = milestoneMsg;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin,
                            () -> sendDiscordWebhook(finalUrl, finalMsg));
                }
            }
        }, 20L, 20L);
    }

    private void broadcastCountdown() {
        Bukkit.broadcastMessage(color(plugin.getConfigManager().getMsgCountdownSay()
                .replace("{seconds}", String.valueOf(secondsLeft))));
    }

    private void sendTitleToAll() {
        ConfigManager cfg      = plugin.getConfigManager();
        String        title    = color(cfg.getMsgTitle());
        String        subtitle = color(cfg.getMsgSubtitle()
                .replace("{seconds}", String.valueOf(secondsLeft)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(title, subtitle, 5, 25, 10);
        }
    }

    // ── Core reset execution ───────────────────────────────────────────────

    /**
     * Master reset method.  All steps are documented inline.
     *
     * The key threading contract:
     *   • Everything before the async block runs on the MAIN thread.
     *   • Heavy I/O (backup + deletion + HTTP) runs on an ASYNC thread.
     *   • Everything from createWorld() onward runs back on the MAIN thread.
     */
    /**
     * Master reset method. Decomposed into prep, async, and finalize phases.
     */
    private void executeReset() {
        ConfigManager cfg = plugin.getConfigManager();

        // 1. Fire Start Event
        WorldResetStartEvent startEvent = new WorldResetStartEvent(currentInitiator, cfg.getWorldsToReset());
        Bukkit.getPluginManager().callEvent(startEvent);
        if (startEvent.isCancelled()) {
            log.info("World reset aborted by a plugin (WorldResetStartEvent cancelled).");
            resetPending = false;
            secondsLeft = 0;
            return;
        }

        log.info("=== WorldReset executing ===");

        // 2. Phase 1 (MAIN): Preparation
        if (!prepareReset(cfg)) return;

        // 3. Phase 2 (ASYNC): Backup + Deletion + Discord
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Discord START notification (Async to prevent main thread lag)
            String webhookUrl = cfg.getDiscordWebhookUrl();
            if (webhookUrl != null && !webhookUrl.isEmpty()) {
                sendDiscordWebhook(webhookUrl, buildDiscordMessage(cfg.getDiscordStartTemplate(), false, 0));
            }

            if (cfg.isBackupEnabled()) {
                backupWorlds();
            }

            performAsyncDeletion(cfg);

            // 4. Phase 3 (MAIN): Finalize
            Bukkit.getScheduler().runTask(plugin, () -> finalizeReset(cfg));
        });
    }

    /**
     * Phase 1 of reset: Kick players, pre-commands, unload worlds. Main thread.
     * @return true if preparation succeeded
     */
    private boolean prepareReset(ConfigManager cfg) {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            p.kickPlayer(cfg.getKickMessage());
        }

        if (cfg.isWhitelistDuringReset()) {
            Bukkit.setWhitelist(true);
            log.info("Whitelist enabled for reset duration.");
        }

        for (String cmd : cfg.getPreResetCommands()) {
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                if (!ok && cfg.isPreResetCommandsStrict()) {
                    log.severe("Pre-reset command failed (strict): '" + cmd + "' — aborting.");
                    abortReset("Pre-reset command failed.");
                    return false;
                }
            } catch (Exception e) {
                log.warning("Pre-reset command error '" + cmd + "': " + e.getMessage());
                if (cfg.isPreResetCommandsStrict()) {
                    abortReset("Pre-reset command exception.");
                    return false;
                }
            }
        }

        savePreservedRegions();

        for (String worldName : cfg.getWorldsToReset()) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                boolean ok = Bukkit.unloadWorld(world, false);
                log.info((ok ? "Unloaded" : "Failed to unload") + " world: " + worldName);
            }
        }
        return true;
    }

    private void abortReset(String reason) {
        Bukkit.broadcastMessage(color("§c[RESET] " + reason + " — reset aborted."));
        resetPending = false;
        secondsLeft = 0;
    }

    /**
     * Phase 2 of reset: Delete world folders and extra paths. Async thread.
     */
    private void performAsyncDeletion(ConfigManager cfg) {
        deleteWorldFolders();
        deleteExtraPaths();
    }

    /**
     * Phase 3 of reset: Regenerate worlds, apply settings, post-commands. Main thread.
     */
    private void finalizeReset(ConfigManager cfg) {
        if (cfg.isServerPropertiesEnabled()) {
            writeServerProperties();
        }

        if (cfg.isUseRestart()) {
            handleResetRestart(cfg);
        } else {
            handleResetLive(cfg);
        }
    }

    private void handleResetRestart(ConfigManager cfg) {
        Map<String, String> patches = cfg.getServerPropertiesValues();
        log.info("[Phase 3] Resolving seeds for " + cfg.getWorldsToReset().size() + " worlds...");
        
        for (String wn : cfg.getWorldsToReset()) {
            Long lockedSeed = cfg.getSeedForWorld(wn);
            // Priority 1: server.properties patches
            if (cfg.isServerPropertiesEnabled() && patches.containsKey("level-seed")) {
                try { lockedSeed = Long.parseLong(patches.get("level-seed")); } catch (Exception ignored) {}
            }

            boolean hc = cfg.isHardcoreForWorld(wn);
            if (cfg.isServerPropertiesEnabled() && patches.containsKey("hardcore")) {
                hc = Boolean.parseBoolean(patches.get("hardcore"));
            }

            long finalSeed = (lockedSeed != null) ? lockedSeed : new java.security.SecureRandom().nextLong();
            writeSeedToLevelDat(wn, finalSeed, hc);
            log.info("[Seed] '" + wn + "' → " + finalSeed + (hc ? " [HARDCORE]" : ""));
        }

        writePendingRestartFlag();
        log.info("Triggering server restart…");

        try {
            Bukkit.getServer().spigot().restart();
        } catch (Exception e) {
            log.severe("Restart failed: " + e.getMessage());
        } finally {
            recordResetCompletion("restart");
        }
    }

    private void handleResetLive(ConfigManager cfg) {
        if (cfg.isUseTemplate()) {
            copyTemplates(cfg);
        } else {
            regenerateWorlds();
        }
        
        pastePreservedRegions();
        applyGamerulesAndDifficulty();
        applyWorldBorder();
        applySpawn();

        if (cfg.isPlayerDataEnabled()) {
            clearPlayerData();
        }

        if (cfg.isWhitelistDuringReset()) {
            Bukkit.setWhitelist(false);
        }

        for (String cmd : cfg.getPostResetCommands()) {
            try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); } catch (Exception ignored) {}
        }

        Bukkit.broadcastMessage(color(cfg.getMsgResetComplete()));
        recordResetCompletion("live-regeneration");
    }

    private void copyTemplates(ConfigManager cfg) {
        File templateRoot = new File(serverRoot(), cfg.getTemplateDirectory());
        if (!templateRoot.exists()) {
            log.severe("Template directory not found: " + templateRoot.getAbsolutePath() + ". Falling back to regeneration.");
            regenerateWorlds();
            return;
        }

        File container = Bukkit.getWorldContainer();
        for (String worldName : cfg.getWorldsToReset()) {
            File source = new File(templateRoot, worldName);
            if (!source.exists()) {
                log.warning("Template for '" + worldName + "' not found. Regenerating standard world.");
                Bukkit.createWorld(new WorldCreator(worldName));
                continue;
            }

            File dest = new File(container, worldName);
            log.info("Copying template for '" + worldName + "'...");
            try {
                copyDirectory(source.toPath(), dest.toPath());
                Bukkit.createWorld(new WorldCreator(worldName));
            } catch (IOException e) {
                log.severe("Failed to copy template for '" + worldName + "': " + e.getMessage());
                Bukkit.createWorld(new WorldCreator(worldName));
            }
        }
    }

    private void copyDirectory(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = dest.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dest.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void recordResetCompletion(String mode) {
        long durationSecs = (System.currentTimeMillis() - resetStartMs) / 1000L;
        plugin.getHistoryManager().logReset(currentInitiator, mode, plugin.getConfigManager().getWorldsToReset(), durationSecs);
        
        Bukkit.getPluginManager().callEvent(new WorldResetCompleteEvent(
                currentInitiator, mode, plugin.getConfigManager().getWorldsToReset(), durationSecs));

        lastResetCompleteMs = System.currentTimeMillis();
        resetPending = false;
        secondsLeft = 0;
        log.info("=== WorldReset complete ===");
    }

    // ── Post-restart apply ─────────────────────────────────────────────────

    /**
     * Write a marker file that {@code WorldResetPlugin.onEnable()} checks on
     * the next startup.  When found, all post-reset steps (gamerules,
     * difficulty, world border, spawn, whitelist, post-commands) are applied
     * to the freshly-created worlds after the server restarts.
     *
     * <p>The file stores the reset initiator and start timestamp as plain
     * {@code key=value} lines so the startup Discord notification can report
     * <em>who</em> triggered the reset and <em>when</em> it began.</p>
     */
    private void writePendingRestartFlag() {
        File flag = new File(plugin.getDataFolder(), "pending-post-reset.flag");
        try {
            plugin.getDataFolder().mkdirs();
            try (java.io.PrintWriter pw =
                         new java.io.PrintWriter(new java.io.FileWriter(flag, false))) {
                pw.println("initiator=" + (currentInitiator != null ? currentInitiator : "Unknown"));
                pw.println("reset_time=" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date()));
            }
            log.info("[PostRestart] Wrote pending-post-reset.flag (initiator="
                    + currentInitiator + ") — post-reset steps will apply on next startup.");
        } catch (IOException e) {
            log.warning("[PostRestart] Could not write pending-post-reset.flag: "
                    + e.getMessage()
                    + " — gamerules/difficulty will NOT be applied after restart.");
        }
    }

    /**
     * Apply all post-reset world steps to the currently loaded worlds.
     * Called:
     * <ul>
     *   <li>Directly after live-regeneration (no restart).</li>
     *   <li>From {@code WorldResetPlugin.onEnable()} when the server detects
     *       a {@code pending-post-reset.flag} left by a restart-mode reset.</li>
     * </ul>
     *
     * Does NOT clear player data (already handled before the restart) or
     * broadcast the reset-complete message — the caller is responsible for
     * those to avoid double-sending.
     */
    public void applyPostResetSteps() {
        ConfigManager cfg = plugin.getConfigManager();
        pastePreservedRegions();
        applyGamerulesAndDifficulty();
        applyWorldBorder();
        applySpawn();

        // Disable whitelist that was enabled before the restart (if configured).
        if (cfg.isWhitelistDuringReset()) {
            Bukkit.setWhitelist(false);
            log.info("[PostRestart] Whitelist disabled after post-restart apply.");
        }

        // Run post-reset console commands.
        for (String cmd : cfg.getPostResetCommands()) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception e) {
                log.warning("Post-reset command failed '" + cmd + "': " + e.getMessage());
            }
        }

        log.info("[PostRestart] Post-reset steps applied to all worlds.");
    }

    // ── WorldEdit Region Preservation ──────────────────────────────────────

    private void savePreservedRegions() {
        if (plugin.getRegionManager() == null) return;
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isRegionsEnabled()) return;

        for (Map<?, ?> map : cfg.getPreservedRegions()) {
            try {
                String name = (String) map.get("name");
                String worldName = (String) map.get("world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    log.warning("Region '" + name + "' world '" + worldName + "' not found. Skipping save.");
                    continue;
                }

                Map<?, ?> minMap = (Map<?, ?>) map.get("min");
                Map<?, ?> maxMap = (Map<?, ?>) map.get("max");

                Location min = new Location(world, (double) minMap.get("x"), (double) minMap.get("y"), (double) minMap.get("z"));
                Location max = new Location(world, (double) maxMap.get("x"), (double) maxMap.get("y"), (double) maxMap.get("z"));

                if (plugin.getRegionManager().saveRegion(name, min, max)) {
                    log.info("Region '" + name + "' saved successfully.");
                } else {
                    log.warning("Region '" + name + "' failed to save.");
                }
            } catch (Exception e) {
                log.warning("Failed to parse region for saving: " + e.getMessage());
            }
        }
    }

    private void pastePreservedRegions() {
        if (plugin.getRegionManager() == null) return;
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isRegionsEnabled()) return;

        for (Map<?, ?> map : cfg.getPreservedRegions()) {
            try {
                String name = (String) map.get("name");
                String worldName = (String) map.get("world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    log.warning("Region '" + name + "' world '" + worldName + "' not found. Skipping paste.");
                    continue;
                }

                Map<?, ?> pasteMap = (Map<?, ?>) map.get("paste_at");
                Location target = new Location(world, (double) pasteMap.get("x"), (double) pasteMap.get("y"), (double) pasteMap.get("z"));

                if (plugin.getRegionManager().pasteRegion(name, target)) {
                    log.info("Region '" + name + "' pasted successfully.");
                } else {
                    log.warning("Region '" + name + "' failed to paste.");
                }
            } catch (Exception e) {
                log.warning("Failed to parse region for pasting: " + e.getMessage());
            }
        }
    }

    // ── File operations ────────────────────────────────────────────────────

    /**
     * Returns the server root directory.
     *
     * FIX 4: Uses new File(".").getCanonicalFile() (the JVM working directory).
     * The old getDataFolder().getParentFile().getParentFile() was fragile when
     * the server was launched with a working directory different from its install path.
     */
    private File serverRoot() {
        try {
            return new File(".").getCanonicalFile();
        } catch (IOException e) {
            // Fallback: WorldContainer's parent reliably points to the server root
            // because worlds are stored directly under the server root.
            File wc = Bukkit.getWorldContainer();
            if (wc != null && wc.getParentFile() != null) {
                return wc.getParentFile();
            }
            // Last resort — should never happen
            log.warning("Could not determine server root via canonical path or WorldContainer; "
                    + "falling back to data folder parent. Path: "
                    + plugin.getDataFolder().getAbsolutePath());
            return plugin.getDataFolder().getAbsoluteFile()
                    .getParentFile()   // plugins/
                    .getParentFile();  // server root
        }
    }

    /**
     * Deletes world folders (without unloading — that is done on the main thread
     * in executeReset() before this method is called on the async worker).
     */
    private void deleteWorldFolders() {
        ConfigManager cfg     = plugin.getConfigManager();
        List<String>  preserve = cfg.getPreservePaths();
        File          root    = serverRoot();
        File          worldContainer = Bukkit.getWorldContainer();

        for (String worldName : cfg.getWorldsToReset()) {
            File worldDir = new File(worldContainer, worldName);
            File levelDat = new File(worldDir, "level.dat");

            if (!worldDir.exists()) continue;

            // 🔌 Multiverse Integration (Unregister before delete)
            if (plugin.getIntegrationManager().hasMultiverse()) {
                plugin.getIntegrationManager().getMultiverse().unregisterWorld(worldName);
            }

            try {
                WorldResetUtil.deleteDirectoryNio(worldDir.toPath(), preserve, root.toPath());
            } catch (IOException e) {
                log.severe("Failed to delete world folder '" + worldName + "': " + e.getMessage());
            }

            // Last-chance forced delete on level.dat
            if (levelDat.exists()) {
                deleteWithRetry(levelDat.toPath());
            }
        }
    }

    private void deleteExtraPaths() {
        ConfigManager cfg     = plugin.getConfigManager();
        List<String>  preserve = cfg.getPreservePaths();
        File          root    = serverRoot();

        for (String relPath : cfg.getExtraDeletePaths()) {
            File target = new File(root, relPath);
            if (!target.exists()) continue;

            try {
                WorldResetUtil.deleteDirectoryNio(target.toPath(), preserve, root.toPath());
            } catch (IOException e) {
                log.warning("Failed to delete extra path '" + relPath + "': " + e.getMessage());
            }
        }
    }


    /**
     * Delete a single file with a short retry loop for OS file-lock races.
     * Logs SEVERE if every attempt fails.
     *
     * NOTE: Thread.sleep() here is intentional — this method is only called
     * from the async worker thread so it will not block the main server thread.
     */
    private void deleteWithRetry(Path file) {
        for (int attempt = 1; attempt <= DELETE_RETRIES; attempt++) {
            try {
                Files.delete(file);
                return;
            } catch (IOException e) {
                if (attempt < DELETE_RETRIES) {
                    log.warning("Cannot delete " + file.getFileName()
                            + " (attempt " + attempt + "/" + DELETE_RETRIES + "): "
                            + e.getMessage() + " — retrying in " + DELETE_RETRY_MS + "ms");
                    try { Thread.sleep(DELETE_RETRY_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                } else {
                    log.severe("FAILED to delete after " + DELETE_RETRIES + " attempts: "
                            + file.toAbsolutePath() + " — " + e.getMessage());
                }
            }
        }
    }

    // ── Backup ─────────────────────────────────────────────────────────────

    /**
     * Public entry point for a manual backup triggered by
     * {@code /worldreset backup now}.  Must be called from an async thread.
     */
    public void runManualBackup() {
        backupWorlds();
    }

    /**
     * ZIP each world folder into {@code backup.directory}, then prune old
     * archives so at most {@code backup.keep} backups exist per world.
     *
     * Runs on the ASYNC worker thread — no Bukkit API calls here.
     */
    private void backupWorlds() {
        ConfigManager cfg       = plugin.getConfigManager();
        File          root      = serverRoot();
        File          worldContainer = Bukkit.getWorldContainer();
        File          backupDir = new File(root, cfg.getBackupDirectory());
        if (!backupDir.mkdirs() && !backupDir.isDirectory()) {
            log.severe("Cannot create backup directory: " + backupDir.getAbsolutePath());
            return;
        }

        // Fire Start Event
        Bukkit.getScheduler().runTask(plugin, () -> 
            Bukkit.getPluginManager().callEvent(new WorldBackupStartEvent(currentInitiator, cfg.getWorldsToReset())));

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        List<String> results = new ArrayList<>();
        boolean anyFailed = false;

        for (String worldName : cfg.getWorldsToReset()) {
            File worldDir = new File(worldContainer, worldName);
            if (!worldDir.exists()) continue;

            File zipFile = new File(backupDir, worldName + "_" + timestamp + ".zip");
            log.info("Backing up '" + worldName + "' → " + zipFile.getName() + " …");

            // 🔍 Disk Space Check
            long worldSize = SecurityUtil.getDirectorySize(worldDir);
            long freeSpace = SecurityUtil.getFreeSpace(backupDir);
            if (freeSpace < worldSize * 1.2) { // 20% buffer for safety
                log.severe("Skipping backup for '" + worldName + "': INSUFFICIENT DISK SPACE. Need ~" + (worldSize/1024/1024) + "MB, have " + (freeSpace/1024/1024) + "MB.");
                results.add("❌ `" + worldName + "` — Insufficient disk space");
                continue;
            }

            try {
                WorldResetUtil.zipDirectory(worldDir, zipFile);
                long sizeMb = zipFile.length() / 1_048_576L;
                
                // 🔐 Generate Checksum
                SecurityUtil.createChecksum(zipFile);
                
                log.info("Backup complete: " + zipFile.getName() + " (" + sizeMb + " MB) + checksum created.");
                results.add("✅ `" + worldName + "` (" + sizeMb + " MB)");
            } catch (IOException | NoSuchAlgorithmException e) {
                log.severe("Backup FAILED for '" + worldName + "': " + e.getMessage());
                results.add("❌ `" + worldName + "` — " + e.getMessage());
                anyFailed = true;
            }

            // Prune stale archives after each new one is written
            pruneOldBackups(backupDir, worldName, cfg.getBackupKeep());
        }

        // ── Discord Notification ──────────────────────────────────────────
        String webhookUrl = cfg.getDiscordWebhookUrl();
        if (!results.isEmpty() && webhookUrl != null && !webhookUrl.isEmpty()) {
            String title = anyFailed ? "⚠️ **World Backup COMPLETED WITH ERRORS**" : "💾 **World Backup COMPLETE**";
            String msg = title + " — **" + Bukkit.getServer().getName() + "**\n"
                    + "Timestamp: `" + timestamp + "`\n"
                    + String.join("\n", results);
            sendDiscordWebhook(webhookUrl, msg);
        }

        // Summary Event
        if (!results.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> 
                Bukkit.getPluginManager().callEvent(new WorldBackupCompleteEvent(cfg.getWorldsToReset(), "Backup_" + timestamp, 0)));
        }
    }

    /**
     * Delete the oldest ZIP archives for {@code worldName} in {@code backupDir},
     * keeping only the {@code keep} most-recent ones.
     *
     * <p>This is called both after each new backup is created (from
     * {@link #backupWorlds()}) and on plugin enable via
     * {@link WorldResetPlugin#onEnable()} so that old archives accumulated
     * while backups were disabled are cleaned up immediately.</p>
     *
     * @param backupDir directory that contains the archives
     * @param worldName world name prefix used when listing archives
     * @param keep      maximum number of archives to retain
     */
    public void pruneOldBackups(File backupDir, String worldName, int keep) {
        if (!backupDir.isDirectory()) return;
        final String prefix = worldName + "_";
        File[] archives = backupDir.listFiles(
                f -> f.getName().startsWith(prefix) && f.getName().endsWith(".zip"));
        if (archives == null || archives.length <= keep) return;
        Arrays.sort(archives, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < archives.length - keep; i++) {
            if (archives[i].delete()) {
                log.info("Pruned old backup: " + archives[i].getName());
            } else {
                log.warning("Could not prune: " + archives[i].getName());
            }
        }
    }

    /**
     * Run a startup prune sweep across all configured worlds.
     * Safe to call on the main thread — no I/O beyond directory listing and
     * file deletion (fast for small numbers of archives).
     */
    public void pruneAllOldBackups() {
        ConfigManager cfg       = plugin.getConfigManager();
        File          root      = serverRoot();
        File          backupDir = new File(root, cfg.getBackupDirectory());
        if (!backupDir.isDirectory()) return;
        int keep = cfg.getBackupKeep();
        for (String worldName : cfg.getWorldsToReset()) {
            pruneOldBackups(backupDir, worldName, keep);
        }
    }

    /**
     * Returns a list of all ZIP files in the configured backup directory,
     * sorted by last modified (newest first).
     */
    public List<File> getAvailableBackups() {
        ConfigManager cfg = plugin.getConfigManager();
        File backupDir = new File(serverRoot(), cfg.getBackupDirectory());
        if (!backupDir.exists() || !backupDir.isDirectory()) return Collections.emptyList();

        File[] files = backupDir.listFiles(f -> f.getName().toLowerCase().endsWith(".zip"));
        if (files == null) return Collections.emptyList();

        List<File> list = Arrays.asList(files);
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        return list;
    }

    /**
     * Initiates a backup restoration process.
     * 
     * WARNING: This follows the same destructive flow as a reset (kick, unload, delete)
     * but replaces regeneration with extraction from the ZIP.
     * 
     * @param initiator name of the player or console who started the restore
     * @param zipName filename of the ZIP archive to restore (must be in backup dir)
     */
    public void restoreBackup(String initiator, String zipName) {
        if (resetPending) return;

        ConfigManager cfg = plugin.getConfigManager();
        File backupDir = new File(serverRoot(), cfg.getBackupDirectory());
        File zipFile = new File(backupDir, zipName);

        if (!zipFile.exists()) {
            log.severe("Restoration FAILED: ZIP file not found: " + zipFile.getAbsolutePath());
            return;
        }

        // Fire Start Event
        WorldRestoreStartEvent startEvent = new WorldRestoreStartEvent(initiator, zipName);
        Bukkit.getPluginManager().callEvent(startEvent);
        if (startEvent.isCancelled()) return;

        resetPending = true;
        currentInitiator = initiator;
        resetStartMs = System.currentTimeMillis();

        // Discord Start
        String webhookUrl = cfg.getDiscordWebhookUrl();
        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            String msg = "🔄 **Backup Restoration STARTED** — **" + Bukkit.getServer().getName() + "**\n"
                    + "**Initiated by:** " + initiator + "\n"
                    + "**Restoring:** `" + zipName + "`\n"
                    + "⚠️ *Server will be unavailable during extraction.*";
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> sendDiscordWebhook(webhookUrl, msg));
        }

        // ── Phase 1 (MAIN): Preparation ────────────────────────────────────
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            p.kickPlayer("§cServer is restoring a backup. Please wait…");
        }

        if (cfg.isWhitelistDuringReset()) Bukkit.setWhitelist(true);

        for (String worldName : cfg.getWorldsToReset()) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                log.info("Unloading '" + worldName + "' for restoration…");
                Bukkit.unloadWorld(world, false);
            }
        }

        // ── Phase 2 (ASYNC): Safe Extraction ───────────────────────────────
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // 🔐 Checksum Verification
                if (!SecurityUtil.verifyChecksum(zipFile)) {
                    throw new IOException("CHECKSUM MISMATCH or MISSING for '" + zipName + "'. Archive may be corrupt.");
                }

                File worldContainer = Bukkit.getWorldContainer();
                
                // 🔍 Disk Space Check (Extract)
                long freeSpace = SecurityUtil.getFreeSpace(worldContainer);
                if (freeSpace < zipFile.length() * 3) { // Assume 3x compression ratio for safety
                     throw new IOException("INSUFFICIENT DISK SPACE for extraction. Need ~" + (zipFile.length()*3/1024/1024) + "MB.");
                }

                File tempDir = new File(plugin.getDataFolder(), "temp_restore_" + System.currentTimeMillis());
                tempDir.mkdirs();

                log.info("Extracting '" + zipName + "' to temporary folder…");
                WorldResetUtil.unzip(zipFile, tempDir);
                log.info("Extraction successful.");

                // Now safe to delete current worlds
                for (String worldName : cfg.getWorldsToReset()) {
                    File worldDir = new File(worldContainer, worldName);
                    if (worldDir.exists()) {
                        log.info("Deleting active '" + worldName + "' before swap…");
                        WorldResetUtil.deleteDirectoryNio(worldDir.toPath(), Collections.emptyList(), serverRoot().toPath());
                    }
                }

                // Move from temp to container
                log.info("Moving restored folders to container…");
                File[] extracted = tempDir.listFiles();
                if (extracted != null) {
                    for (File f : extracted) {
                        File target = new File(worldContainer, f.getName());
                        f.renameTo(target);
                    }
                }
                
                // Cleanup temp dir
                tempDir.delete();

                // ── Phase 3 (MAIN): Finalize ─────────────────────────────────
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (cfg.isUseRestart()) {
                        handleRestoreRestart(cfg, zipName, webhookUrl);
                    } else {
                        handleRestoreLive(cfg, zipName, webhookUrl);
                    }
                });

            } catch (IOException e) {
                handleRestoreFailure(e, zipName, webhookUrl);
            }
        });
    }

    private void handleRestoreRestart(ConfigManager cfg, String zipName, String webhookUrl) {
        log.info("Restoration complete. Restarting server...");
        plugin.getServer().spigot().restart();
        recordRestoreCompletion(zipName, webhookUrl);
    }

    private void handleRestoreLive(ConfigManager cfg, String zipName, String webhookUrl) {
        log.info("Restoration complete. Loading worlds…");
        for (String worldName : cfg.getWorldsToReset()) {
            Bukkit.createWorld(new WorldCreator(worldName));
        }

        applyGamerulesAndDifficulty();
        applyWorldBorder();
        applySpawn();

        if (cfg.isWhitelistDuringReset()) Bukkit.setWhitelist(false);

        recordRestoreCompletion(zipName, webhookUrl);
        log.info("Restoration finished.");
    }

    private void recordRestoreCompletion(String zipName, String webhookUrl) {
        long duration = (System.currentTimeMillis() - resetStartMs) / 1000;
        resetPending = false;
        lastResetCompleteMs = System.currentTimeMillis();

        // Fire Event
        Bukkit.getPluginManager().callEvent(new WorldRestoreCompleteEvent(zipName, duration));

        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            String msg = "✅ **Backup Restoration COMPLETE** — **" + Bukkit.getServer().getName() + "**\n"
                    + "**Restored:** `" + zipName + "`\n"
                    + "⏱️ **Duration:** " + formatDuration(duration);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> sendDiscordWebhook(webhookUrl, msg));
        }
    }

    private void handleRestoreFailure(Exception e, String zipName, String webhookUrl) {
        log.severe("Restoration FAILED: " + e.getMessage());
        resetPending = false;

        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            String msg = "❌ **Backup Restoration FAILED** — **" + Bukkit.getServer().getName() + "**\n"
                            + "**Error:** " + e.getMessage();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> sendDiscordWebhook(webhookUrl, msg));
        }
    }

    // ── World regeneration ─────────────────────────────────────────────────

    /**
     * Recreates every world in the reset list on the main thread.
     *
     * Environment priority: explicit override in {@code world-environments} config
     * → inferred from world name suffix.
     *
     * Generator: custom generator string from {@code world-generators} config
     * if set, otherwise vanilla.
     *
     * Seed: locked value from {@code seeds} config, or a fresh SecureRandom seed.
     */
    private void regenerateWorlds() {
        ConfigManager cfg = plugin.getConfigManager();
        File worldContainer = Bukkit.getWorldContainer();

        for (String worldName : cfg.getWorldsToReset()) {
            File worldDir = new File(worldContainer, worldName);
            File levelDat = new File(worldDir, "level.dat");

            if (levelDat.exists()) {
                // One final synchronous attempt on the main thread before giving up.
                // The async worker may have released its file handles by now.
                log.warning("level.dat still on disk for '" + worldName
                        + "' — making one final main-thread deletion attempt.");
                deleteWithRetry(levelDat.toPath());
            }

            if (levelDat.exists()) {
                // Still present — Bukkit.createWorld() would read it and reuse the old
                // seed.  We continue anyway so the world gets loaded (server stays
                // functional), but log a clear message so the admin knows why the seed
                // did not change.
                log.severe("CANNOT delete level.dat for '" + worldName + "': "
                        + levelDat.getAbsolutePath());
                log.severe("The old seed will be reused for '" + worldName
                        + "'. The world will still load.  Check for file locks "
                        + "(antivirus, another process) and reset again to apply the new seed.");
                // Fall through — do NOT skip; createWorld() will load the existing folder.
            }

            // ── Environment ────────────────────────────────────────────────
            World.Environment env;
            String envOverride = cfg.getWorldEnvironment(worldName);
            if (envOverride != null && !envOverride.isEmpty()) {
                try {
                    env = World.Environment.valueOf(envOverride.toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warning("Invalid world-environment '" + envOverride + "' for world '"
                            + worldName + "' — inferring from name.");
                    env = environmentFor(worldName);
                }
            } else {
                env = environmentFor(worldName);
            }

            // ── Hardcore ───────────────────────────────────────────────────
            boolean hardcore = cfg.isHardcoreForWorld(worldName);
            Map<String, String> patches = cfg.getServerPropertiesValues();
            if (cfg.isServerPropertiesEnabled() && patches.containsKey("hardcore")) {
                hardcore = Boolean.parseBoolean(patches.get("hardcore"));
            }

            // ── WorldCreator ───────────────────────────────────────────────
            WorldCreator creator = new WorldCreator(worldName)
                    .environment(env)
                    .hardcore(hardcore);

            // Custom generator plugin
            String generator = cfg.getWorldGenerator(worldName);
            if (generator != null && !generator.isEmpty()) {
                creator.generator(generator);
                log.info("Using custom generator '" + generator + "' for '" + worldName + "'.");
            }

            // ── Seed ───────────────────────────────────────────────────────
            Long lockedSeed = cfg.getSeedForWorld(worldName);

            // Priority 1: server.properties patches (level-seed)
            if (cfg.isServerPropertiesEnabled() && patches.containsKey("level-seed")) {
                String patchSeed = patches.get("level-seed");
                try {
                    lockedSeed = Long.parseLong(patchSeed);
                } catch (NumberFormatException e) {
                    log.warning("Invalid level-seed patch '" + patchSeed + "' — using config seed.");
                }
            }

            long finalSeed;
            if (lockedSeed != null) {
                finalSeed = lockedSeed;
                log.info("Regenerating '" + worldName + "' with LOCKED seed: " + finalSeed
                        + (hardcore ? " [HARDCORE]" : ""));
            } else {
                finalSeed = new java.security.SecureRandom().nextLong();
                log.info("Regenerating '" + worldName + "' with NEW random seed: " + finalSeed
                        + (hardcore ? " [HARDCORE]" : ""));
            }

            creator.seed(finalSeed);
            // Pre-write level.dat with the chosen seed.
            // This is the reliable way to guarantee the seed — WorldCreator.seed()
            // alone can be ignored by Paper if a stale level.dat survives deletion.
            writeSeedToLevelDat(worldName, finalSeed, hardcore);

            World created = Bukkit.createWorld(creator);
            if (created != null) {
                long actualSeed = created.getSeed();
                log.info("  → '" + worldName + "' online. Actual seed: " + actualSeed
                        + "  Hardcore: " + created.isHardcore());

                long expectedSeed = lockedSeed != null ? lockedSeed : creator.seed();
                if (actualSeed != expectedSeed) {
                    log.warning("  !! Seed mismatch for '" + worldName + "' — requested "
                            + expectedSeed + " got " + actualSeed
                            + ". level.dat may not have been fully deleted.");
                }

                // 🔌 Multiverse Integration (Re-register after creation)
                if (plugin.getIntegrationManager().hasMultiverse()) {
                    plugin.getIntegrationManager().getMultiverse().registerWorld(worldName, env);
                }
            } else {
                log.warning("Bukkit.createWorld() returned null for: " + worldName);
            }
        }
    }

    /**
     * Infer world environment from name suffix — vanilla convention.
     * config world-environments overrides take priority over this in regenerateWorlds().
     */
    private World.Environment environmentFor(String name) {
        if (name.endsWith("_nether"))  return World.Environment.NETHER;
        if (name.endsWith("_the_end")) return World.Environment.THE_END;
        return World.Environment.NORMAL;
    }

    // ── Gamerules + difficulty + hardcore ──────────────────────────────────

    /**
     * Apply gamerules and difficulty to every reset world.
     *
     * FIX 3: Uses the type-safe {@link GameRule} API introduced in Bukkit 1.13.
     * The deprecated {@link World#setGameRuleValue(String, String)} is no longer called.
     */
    public void applyGamerulesAndDifficulty() {
        ConfigManager cfg = plugin.getConfigManager();
        Map<String, String> patches = cfg.getServerPropertiesValues();

        Difficulty difficulty;
        String diffStr = cfg.getDifficulty();

        // Priority 1: server.properties patches
        if (cfg.isServerPropertiesEnabled() && patches.containsKey("difficulty")) {
            diffStr = patches.get("difficulty");
        }

        difficulty = parseDifficulty(diffStr);

        Map<String, String> rules = cfg.getGamerules();

        for (String worldName : cfg.getWorldsToReset()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            world.setDifficulty(difficulty);

            // Hardcore flag on the live world (Bukkit 1.16+)
            boolean hardcore = cfg.isHardcoreForWorld(worldName);
            if (cfg.isServerPropertiesEnabled() && patches.containsKey("hardcore")) {
                hardcore = Boolean.parseBoolean(patches.get("hardcore"));
            }

            try {
                world.setHardcore(hardcore);
            } catch (NoSuchMethodError ignored) {
                // < 1.16 — WorldCreator already set it in level.dat
            }

            for (Map.Entry<String, String> entry : rules.entrySet()) {
                applyGameRule(world, entry.getKey(), entry.getValue());
            }
            log.info("Applied difficulty (" + difficulty + ") + " + rules.size() + " gamerule(s) to '" + worldName + "'.");
        }
    }

    /**
     * Parse difficulty from a string, supporting names (HARD) and numbers (3).
     */
    private Difficulty parseDifficulty(String input) {
        if (input == null) return Difficulty.HARD;
        String val = input.trim().toUpperCase();

        // Try parsing by name
        try {
            return Difficulty.valueOf(val);
        } catch (IllegalArgumentException ignored) {}

        // Try parsing by ID (0=peaceful, 1=easy, 2=normal, 3=hard)
        switch (val) {
            case "0": return Difficulty.PEACEFUL;
            case "1": return Difficulty.EASY;
            case "2": return Difficulty.NORMAL;
            case "3": return Difficulty.HARD;
        }

        log.warning("Invalid difficulty '" + input + "', defaulting to HARD.");
        return Difficulty.HARD;
    }

    /**
     * Type-safe gamerule application — FIX 3.
     *
     * Replaces the deprecated {@code World.setGameRuleValue(String, String)}.
     * {@link GameRule#getByName(String)} covers all vanilla gamerules;
     * unknown names and type-mismatched values are logged as warnings rather
     * than silently swallowed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyGameRule(World world, String ruleName, String value) {
        GameRule<?> rule = GameRule.getByName(ruleName);
        if (rule == null) {
            log.warning("Unknown gamerule '" + ruleName + "' — skipped. "
                    + "Check spelling or update the plugin for newer Minecraft versions.");
            return;
        }

        Class<?> type = rule.getType();
        if (type == Boolean.class) {
            boolean boolVal = Boolean.parseBoolean(value);
            world.setGameRule((GameRule<Boolean>) rule, boolVal);
        } else if (type == Integer.class) {
            try {
                world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(value));
            } catch (NumberFormatException e) {
                log.warning("Gamerule '" + ruleName + "' expects an integer, got '"
                        + value + "' — skipped.");
            }
        } else {
            // Catches any future Bukkit gamerule types gracefully
            log.warning("Gamerule '" + ruleName + "' has unexpected type "
                    + type.getSimpleName() + " — skipped.");
        }
    }

    // ── Post-reset: world border ───────────────────────────────────────────

    public void applyWorldBorder() {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isWorldBorderEnabled()) return;

        double size = cfg.getWorldBorderSize();
        double cx   = cfg.getWorldBorderCenterX();
        double cz   = cfg.getWorldBorderCenterZ();

        for (String worldName : cfg.getWorldsToReset()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null || world.getEnvironment() != World.Environment.NORMAL) continue;

            WorldBorder border = world.getWorldBorder();
            border.setCenter(cx, cz);
            border.setSize(size);
            log.info("World border set for '" + worldName + "': size=" + size
                    + ", center=(" + cx + ", " + cz + ")");
        }
    }

    // ── Post-reset: spawn ─────────────────────────────────────────────────

    public void applySpawn() {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isSpawnEnabled()) return;

        String worldName = cfg.getSpawnWorld();
        World  world     = Bukkit.getWorld(worldName);
        if (world == null) {
            log.warning("Spawn world '" + worldName + "' not found — spawn not set.");
            return;
        }

        Location loc = new Location(world,
                cfg.getSpawnX(), cfg.getSpawnY(), cfg.getSpawnZ(),
                cfg.getSpawnYaw(), cfg.getSpawnPitch());
        world.setSpawnLocation(loc);
        log.info("Spawn set to (" + cfg.getSpawnX() + ", " + cfg.getSpawnY() + ", "
                + cfg.getSpawnZ() + ") in world '" + worldName + "'.");
    }

    // ── Post-reset: player data ────────────────────────────────────────────

    /**
     * Clear player data after regeneration.
     *
     * <p>Since all players are kicked before the reset, this primarily handles
     * edge cases (e.g. a player rejoined extremely quickly) and disk-level
     * deletion of advancements/stats folders (as a complement to extra-paths.delete).
     * The in-memory clearing also applies to any operator who is online.</p>
     */
    private void clearPlayerData() {
        ConfigManager cfg  = plugin.getConfigManager();
        File          root = serverRoot();

        // In-memory clear for any player online at this point
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (cfg.isPlayerDataClearInventory()) {
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
            }
            if (cfg.isPlayerDataClearEnderChest()) {
                p.getEnderChest().clear();
            }
            if (cfg.isPlayerDataResetHealth()) {
                p.setHealth(p.getAttribute(
                        org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue());
            }
            if (cfg.isPlayerDataResetHunger()) {
                p.setFoodLevel(20);
                p.setSaturation(5.0f);
            }
            if (cfg.isPlayerDataClearXp()) {
                p.setExp(0);
                p.setLevel(0);
                p.setTotalExperience(0);
            }
        }

        // Disk-level deletion (complementary to extra-paths.delete)
        List<String> noPreserve = Collections.emptyList();
        if (cfg.isPlayerDataClearAdvancements()) {
            File dir = new File(root, "advancements");
            if (dir.exists()) WorldResetUtil.deleteDirectoryNio(dir.toPath(), noPreserve, root.toPath());
        }
        if (cfg.isPlayerDataClearStats()) {
            File dir = new File(root, "stats");
            if (dir.exists()) WorldResetUtil.deleteDirectoryNio(dir.toPath(), noPreserve, root.toPath());
        }
        if (cfg.isPlayerDataClearInventory()) {
            File dir = new File(root, "playerdata");
            if (dir.exists()) WorldResetUtil.deleteDirectoryNio(dir.toPath(), noPreserve, root.toPath());
        }

        log.info("Player data cleared.");
    }

    // ── Post-reset: server.properties ─────────────────────────────────────

    /**
     * Patch {@code server.properties} with the values configured under
     * {@code server-properties.values} in config.yml.
     *
     * <p>Delegates entirely to {@link ServerPropertiesManager} which has
     * already loaded the full file into its cache on plugin startup.
     * Every existing key is preserved; only configured keys are changed.</p>
     *
     * <p>Only called in restart mode — the running JVM does not re-read
     * server.properties mid-session.</p>
     */
    private void writeServerProperties() {
        Map<String, String> patches = plugin.getConfigManager().getServerPropertiesValues();
        if (patches.isEmpty()) {
            log.info("[ServerProps] No patches configured — skipping.");
            return;
        }
        plugin.getServerPropertiesManager().applyPatches(patches);
    }

    // ── Discord template builder ───────────────────────────────────────────

    /**
     * Resolve all {@code {placeholder}} tokens in a Discord message template.
     *
     * <h3>Available placeholders</h3>
     * <pre>
     *  ── Identity ──────────────────────────────────────────────────────────
     *  {server}          — Bukkit server name
     *  {initiator}       — player name, "Schedule", or "Vote"
     *  {mode}            — "restart" or "live-regeneration"
     *  {schedule}        — current reset schedule (CRON, interval, or daily)
     *  {time}            — current date/time (server timezone)
     *  {duration}        — how long the reset took (complete only; "in progress" on start)
     *
     *  ── Worlds ────────────────────────────────────────────────────────────
     *  {worlds}          — comma-separated list of worlds being reset
     *  {worlds_detail}   — per-world: environment, seed (locked/random), hardcore status
     *
     *  ── Seeds & Hardcore ──────────────────────────────────────────────────
     *  {seeds}           — worlds with locked seeds; "(none — random each reset)" if none
     *  {hardcore}        — per-world hardcore flag summary
     *
     *  ── World rules ───────────────────────────────────────────────────────
     *  {gamerules}       — configured gamerule key=value pairs
     *  {difficulty}      — configured difficulty (EASY/NORMAL/HARD/PEACEFUL)
     *
     *  ── World Border ──────────────────────────────────────────────────────
     *  {world_border}    — world border config: enabled/disabled, size, center
     *
     *  ── Spawn ─────────────────────────────────────────────────────────────
     *  {spawn}           — spawn config: enabled/disabled, world, x/y/z
     *
     *  ── Data clearing ─────────────────────────────────────────────────────
     *  {player_data}     — summary of what player data is cleared
     *  {extra_paths}     — paths deleted on reset (logs, playerdata, etc.)
     *  {preserve_paths}  — paths preserved across reset
     *
     *  ── Server properties ─────────────────────────────────────────────────
     *  {server_props}    — key=value pairs patched in server.properties
     *
     *  ── Commands ──────────────────────────────────────────────────────────
     *  {pre_commands}    — pre-reset commands list
     *  {post_commands}   — post-reset commands list
     *
     *  ── Misc ──────────────────────────────────────────────────────────────
     *  {backup}          — backup enabled/disabled status
     *  {whitelist}       — whether whitelist is enabled during the reset
     *  {voter_list}      — player names who voted yes (Vote resets only)
     *  {vote_result}     — e.g. "8 voted yes"  —  "N/A" for non-vote resets
     * </pre>
     */
    private String buildDiscordMessage(String template, boolean isComplete, long durationSecs) {
        if (template == null || template.isEmpty()) return "";
        ConfigManager cfg = plugin.getConfigManager();

        // ── Identity ──────────────────────────────────────────────────────
        String server    = Bukkit.getServer().getName();
        String initiator = currentInitiator != null ? currentInitiator : "Unknown";
        String mode      = cfg.isUseRestart() ? "restart" : "live-regeneration";
        String time      = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String duration  = isComplete ? formatDuration(durationSecs) : "in progress";

        // ── Schedule ──────────────────────────────────────────────────────
        String schedule;
        if (cfg.isScheduleEnabled()) {
            String schMode = cfg.getScheduleMode().toLowerCase();
            if ("cron".equals(schMode)) {
                schedule = "CRON: `" + cfg.getScheduleCron() + "`";
            } else if ("daily".equals(schMode)) {
                schedule = "Daily at " + cfg.getScheduleDailyTime() + " (" + cfg.getScheduleTimezone() + ")";
            } else {
                schedule = "Every " + formatDuration(cfg.getScheduleIntervalSecs());
            }
        } else {
            schedule = "disabled";
        }

        // ── Worlds ────────────────────────────────────────────────────────
        List<String> worldsList = cfg.getWorldsToReset();
        String worlds = String.join(", ", worldsList);

        StringBuilder worldsDetail = new StringBuilder();
        for (String w : worldsList) {
            Long    seed      = cfg.getSeedForWorld(w);
            boolean hardcore  = cfg.isHardcoreForWorld(w);
            String  envOverride = cfg.getWorldEnvironment(w);
            String  env;
            if (envOverride != null && !envOverride.isEmpty()) {
                env = envOverride.toUpperCase();
            } else if (w.endsWith("_nether"))  { env = "NETHER"; }
            else if (w.endsWith("_the_end"))   { env = "THE_END"; }
            else                               { env = "NORMAL"; }

            worldsDetail.append("• **").append(w).append("** [").append(env).append("]");
            worldsDetail.append("  seed=").append(seed != null ? "🔒 " + seed : "🎲 random");
            if (hardcore) worldsDetail.append("  ☠ HARDCORE");
            worldsDetail.append("\n");
        }

        // ── Seeds ─────────────────────────────────────────────────────────
        StringBuilder seedsSb = new StringBuilder();
        boolean anySeeds = false;
        for (String w : worldsList) {
            Long seed = cfg.getSeedForWorld(w);
            if (seed != null) {
                if (seedsSb.length() > 0) seedsSb.append(", ");
                seedsSb.append(w).append(": ").append(seed);
                anySeeds = true;
            }
        }
        String seeds = anySeeds ? seedsSb.toString() : "(none — random each reset)";

        // ── Hardcore ──────────────────────────────────────────────────────
        StringBuilder hcSb = new StringBuilder();
        for (String w : worldsList) {
            boolean hc = cfg.isHardcoreForWorld(w);
            if (hcSb.length() > 0) hcSb.append(", ");
            hcSb.append(w).append(": ").append(hc ? "☠ yes" : "no");
        }
        String hardcore = hcSb.length() > 0 ? hcSb.toString() : "(none)";

        // ── Gamerules ─────────────────────────────────────────────────────
        Map<String, String> gamerules = cfg.getGamerules();
        StringBuilder grSb = new StringBuilder();
        if (gamerules.isEmpty()) {
            grSb.append("(none configured)");
        } else {
            for (Map.Entry<String, String> e : gamerules.entrySet()) {
                if (grSb.length() > 0) grSb.append(", ");
                grSb.append(e.getKey()).append("=").append(e.getValue());
            }
        }

        // ── Difficulty ────────────────────────────────────────────────────
        String difficulty = cfg.getDifficulty();

        // ── World Border ──────────────────────────────────────────────────
        String worldBorder;
        if (cfg.isWorldBorderEnabled()) {
            worldBorder = String.format("enabled  size=%.0f  center=(%.0f, %.0f)",
                    cfg.getWorldBorderSize(),
                    cfg.getWorldBorderCenterX(),
                    cfg.getWorldBorderCenterZ());
        } else {
            worldBorder = "disabled";
        }

        // ── Spawn ─────────────────────────────────────────────────────────
        String spawn;
        if (cfg.isSpawnEnabled()) {
            spawn = String.format("enabled  world=%s  xyz=(%.1f, %.1f, %.1f)",
                    cfg.getSpawnWorld(),
                    cfg.getSpawnX(), cfg.getSpawnY(), cfg.getSpawnZ());
        } else {
            spawn = "disabled";
        }

        // ── Player data ───────────────────────────────────────────────────
        String playerDataSummary;
        if (cfg.isPlayerDataEnabled()) {
            List<String> items = new ArrayList<>();
            if (cfg.isPlayerDataClearInventory())    items.add("inventory");
            if (cfg.isPlayerDataClearEnderChest())   items.add("ender chest");
            if (cfg.isPlayerDataClearXp())           items.add("XP");
            if (cfg.isPlayerDataResetHealth())       items.add("health");
            if (cfg.isPlayerDataResetHunger())       items.add("hunger");
            if (cfg.isPlayerDataClearAdvancements()) items.add("advancements");
            if (cfg.isPlayerDataClearStats())        items.add("stats");
            playerDataSummary = items.isEmpty() ? "enabled (nothing selected)"
                                                : String.join(", ", items);
        } else {
            playerDataSummary = "disabled";
        }

        // ── Extra paths ───────────────────────────────────────────────────
        List<String> extraPaths = cfg.getExtraDeletePaths();
        String extraPathsStr = extraPaths.isEmpty() ? "(none)" : String.join(", ", extraPaths);

        // ── Preserve paths ────────────────────────────────────────────────
        List<String> preservePaths = cfg.getPreservePaths();
        String preservePathsStr = preservePaths.isEmpty() ? "(none)" : String.join(", ", preservePaths);

        // ── Server properties patches ─────────────────────────────────────
        String serverPropsStr;
        if (cfg.isServerPropertiesEnabled()) {
            Map<String, String> patches = new java.util.TreeMap<>(cfg.getServerPropertiesValues());

            // Supplement the patch list with per-world settings if they exist,
            // so admins see everything that's changing in one list.
            for (String wn : cfg.getWorldsToReset()) {
                if (cfg.isHardcoreForWorld(wn)) {
                    patches.put("hardcore." + wn, "true");
                }
                String env = cfg.getWorldEnvironment(wn);
                if (env != null && !env.isEmpty()) {
                    patches.put("environment." + wn, env);
                }
                Long seed = cfg.getSeedForWorld(wn);
                if (seed != null) {
                    patches.put("seed." + wn, String.valueOf(seed));
                }
            }

            if (patches.isEmpty()) {
                serverPropsStr = "enabled (no values configured)";
            } else {
                StringBuilder spSb = new StringBuilder();
                for (Map.Entry<String, String> e : patches.entrySet()) {
                    if (spSb.length() > 0) spSb.append(", ");
                    spSb.append(e.getKey()).append("=").append(e.getValue());
                }
                serverPropsStr = spSb.toString();
            }
        } else {
            serverPropsStr = "disabled";
        }

        // ── Pre / Post commands ───────────────────────────────────────────
        List<String> preCmds  = cfg.getPreResetCommands();
        List<String> postCmds = cfg.getPostResetCommands();
        String preCommandsStr  = preCmds.isEmpty()  ? "(none)" : String.join(", ", preCmds);
        String postCommandsStr = postCmds.isEmpty() ? "(none)" : String.join(", ", postCmds);

        // ── Backup ────────────────────────────────────────────────────────
        String backupStatus = cfg.isBackupEnabled()
                ? "enabled (keep " + cfg.getBackupKeep() + ")" : "disabled";

        // ── Whitelist ─────────────────────────────────────────────────────
        String whitelist = cfg.isWhitelistDuringReset() ? "enabled during reset" : "not changed";

        // ── Vote ──────────────────────────────────────────────────────────
        String voterList  = "N/A";
        String voteResult = "N/A";
        VoteManager vm = plugin.getVoteManager();
        if ("Vote".equals(initiator) && vm != null) {
            List<String> voters = vm.getLastVoterNames();
            if (!voters.isEmpty()) {
                voterList  = String.join(", ", voters);
                voteResult = voters.size() + " voted yes";
            }
        }

        return template
                .replace("{server}",         server)
                .replace("{initiator}",      initiator)
                .replace("{mode}",           mode)
                .replace("{schedule}",       schedule)
                .replace("{time}",           time)
                .replace("{duration}",       duration)
                .replace("{worlds}",         worlds)
                .replace("{worlds_detail}",  worldsDetail.toString().trim())
                .replace("{seeds}",          seeds)
                .replace("{hardcore}",       hardcore)
                .replace("{gamerules}",      grSb.toString())
                .replace("{difficulty}",     difficulty)
                .replace("{world_border}",   worldBorder)
                .replace("{spawn}",          spawn)
                .replace("{player_data}",    playerDataSummary)
                .replace("{extra_paths}",    extraPathsStr)
                .replace("{preserve_paths}", preservePathsStr)
                .replace("{server_props}",   serverPropsStr)
                .replace("{pre_commands}",   preCommandsStr)
                .replace("{post_commands}",  postCommandsStr)
                .replace("{backup}",         backupStatus)
                .replace("{whitelist}",      whitelist)
                .replace("{voter_list}",     voterList)
                .replace("{vote_result}",    voteResult);
    }

    /** Format a duration in seconds to a human-readable string like "2m 47s". */
    private static String formatDuration(long totalSecs) {
        if (totalSecs <= 0) return "<1s";
        long h = totalSecs / 3600;
        long m = (totalSecs % 3600) / 60;
        long s = totalSecs % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    // ── Discord webhook ────────────────────────────────────────────────────

    /**
     * POST a plain {@code content} JSON message to a Discord webhook URL.
     * Must be called from an async thread (blocks on HTTP).
     */
    public void sendDiscordWebhook(String url, String message) {
        String json = "{\"content\":\"" + escapeJson(message) + "\"}";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request,
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Discord webhook delivered (HTTP " + response.statusCode() + ").");
            } else {
                log.warning("Discord webhook returned HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            log.warning("Discord webhook failed: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"",  "\\\"")
                .replace("\n",  "\\n")
                .replace("\r",  "\\r")
                .replace("\t",  "\\t");
    }

    // ── Static helpers ─────────────────────────────────────────────────────

    private static boolean isPreserved(String relativePath, List<String> preservePaths) {
        String norm = relativePath.replace('\\', '/');
        for (String p : preservePaths) {
            String pn = p.replace('\\', '/');
            if (norm.equals(pn) || norm.startsWith(pn + "/")) return true;
        }
        return false;
    }

    private static String relativize(File root, File child) {
        return root.toURI().relativize(child.toURI()).getPath();
    }

    static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ── Seed → level.dat (restart-mode fix) ───────────────────────────────

    /**
     * Write a minimal gzipped NBT {@code level.dat} containing the seed and
     * hardcore flag that Paper/Spigot reads on world creation.
     *
     * <h3>Why this is needed</h3>
     * In restart mode the world folders (including their {@code level.dat}) are
     * deleted before {@code spigot().restart()} is called.  When Paper starts
     * fresh it finds no {@code level.dat} and generates a new random seed,
     * completely ignoring any locked seed stored in the plugin config.
     *
     * <h3>NBT layout written</h3>
     * <pre>
     * TAG_Compound("")
     *   TAG_Compound("Data")
     *     TAG_Long("RandomSeed")          ← read by pre-1.16 Spigot
     *     TAG_Byte("hardcore")            ← 1 = hardcore, 0 = normal
     *     TAG_Compound("WorldGenSettings")
     *       TAG_Long("seed")              ← read by Paper/Spigot 1.16+
     * </pre>
     *
     * @param worldName  name of the world folder (relative to server root)
     * @param seed       the locked seed value to embed
     * @param hardcore   whether to flag the world as hardcore
     */
    private void writeSeedToLevelDat(String worldName, long seed, boolean hardcore) {
        File worldDir = new File(Bukkit.getWorldContainer(), worldName);
        worldDir.mkdirs();
        File levelDat = new File(worldDir, "level.dat");

        try (GZIPOutputStream gzip = new GZIPOutputStream(new FileOutputStream(levelDat));
             DataOutputStream dos  = new DataOutputStream(gzip)) {

            // Root compound: TAG_Compound, name = ""
            writeNbtCompound(dos, "", () -> {
                // "Data" compound
                writeNbtCompound(dos, "Data", () -> {
                    // Use a legacy DataVersion (2230 = 1.15.2) to ensure Paper
                    // performs a clean upgrade. If we use a modern version (1.16+)
                    // but don't provide the complex WorldGenSettings structure (including 'dimensions'),
                    // Paper's codecs will throw an IllegalStateException ("No key dimensions in MapLike").
                    writeNbtInt(dos, "DataVersion", 2230);
                    writeNbtInt(dos, "version", 19133);
                    writeNbtString(dos, "LevelName", worldName);
                    writeNbtByte(dos, "Initialized", (byte) 1);

                    // Legacy seed field (Spigot pre-1.16, honored by Paper as a fallback/upgrade)
                    writeNbtLong(dos, "RandomSeed", seed);
                    // Hardcore flag — Paper/Spigot reads this from level.dat on world load
                    writeNbtByte(dos, "hardcore", (byte) (hardcore ? 1 : 0));
                });
            });

            log.info("[Seed] Authoritatively wrote seed " + seed + " to level.dat for '" + worldName + "'.");
        } catch (IOException e) {
            log.warning("[Seed] Failed to pre-write level.dat for '"
                    + worldName + "': " + e.getMessage());
        }
    }

    /**
     * Write a named TAG_Compound (type 10), call {@code content} for the
     * children, then write a TAG_End (type 0) to close it.
     */
    private static void writeNbtCompound(DataOutputStream dos, String name, NbtBlock content)
            throws IOException {
        dos.writeByte(10);           // TAG_Compound
        writeNbtName(dos, name);
        content.write();
        dos.writeByte(0);            // TAG_End
    }

    /**
     * Write a named TAG_Long (type 4) with the given value.
     */
    private static void writeNbtLong(DataOutputStream dos, String name, long value)
            throws IOException {
        dos.writeByte(4);            // TAG_Long
        writeNbtName(dos, name);
        dos.writeLong(value);
    }

    /**
     * Write a named TAG_Int (type 3) with the given value.
     */
    private static void writeNbtInt(DataOutputStream dos, String name, int value)
            throws IOException {
        dos.writeByte(3);            // TAG_Int
        writeNbtName(dos, name);
        dos.writeInt(value);
    }

    /**
     * Write a named TAG_Byte (type 1) with the given value.
     */
    private static void writeNbtByte(DataOutputStream dos, String name, byte value)
            throws IOException {
        dos.writeByte(1);            // TAG_Byte
        writeNbtName(dos, name);
        dos.writeByte(value);
    }

    /**
     * Write a named TAG_String (type 8) with the given value.
     */
    private static void writeNbtString(DataOutputStream dos, String name, String value)
            throws IOException {
        dos.writeByte(8);            // TAG_String
        writeNbtName(dos, name);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }

    /** Write a length-prefixed UTF-8 tag name as used by the NBT spec. */
    private static void writeNbtName(DataOutputStream dos, String name) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }

    /** Functional interface for checked-IOException lambdas inside NBT helpers. */
    @FunctionalInterface
    private interface NbtBlock { void write() throws IOException; }
}
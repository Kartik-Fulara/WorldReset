package com.worldreset;

import com.worldreset.UpdateChecker;
import com.worldreset.commands.ResetCommand;
import com.worldreset.managers.ConfigManager;
import com.worldreset.managers.HistoryManager;
import com.worldreset.managers.ResetManager;
import com.worldreset.managers.ScheduleManager;
import com.worldreset.managers.ServerPropertiesManager;
import com.worldreset.managers.VoteManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class WorldResetPlugin extends JavaPlugin {

    private static WorldResetPlugin instance;
    private ConfigManager            configManager;
    private ResetManager             resetManager;
    private ScheduleManager          scheduleManager;
    private ServerPropertiesManager  serverPropertiesManager;
    private HistoryManager           historyManager;
    private VoteManager              voteManager;
    private UpdateChecker            updateChecker;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config.yml if not present
        saveDefaultConfig();

        // Init managers
        configManager = new ConfigManager(this);
        configManager.initServerPropsFile();   // creates / loads server-properties.yml

        serverPropertiesManager = new ServerPropertiesManager(this);
        serverPropertiesManager.load();        // reads server.properties into memory

        historyManager  = new HistoryManager(this);
        resetManager    = new ResetManager(this);
        scheduleManager = new ScheduleManager(this);

        // Register /worldreset
        ResetCommand cmd = new ResetCommand(this);
        getCommand("worldreset").setExecutor(cmd);
        getCommand("worldreset").setTabCompleter(cmd);

        // Register /resetvote
        voteManager = new VoteManager(this);
        getCommand("resetvote").setExecutor(voteManager);
        getCommand("resetvote").setTabCompleter(voteManager);

        // Start auto-reset schedule if configured
        scheduleManager.start();

        // ── Post-restart apply ─────────────────────────────────────────────
        // ResetManager writes pending-post-reset.flag (with initiator + reset_time)
        // before calling spigot().restart().  We read those values back here so
        // the Discord startup notification can show exactly who triggered the reset.
        File pendingFlag = new File(getDataFolder(), "pending-post-reset.flag");
        boolean isPostReset     = pendingFlag.exists();
        String  resetInitiator  = "Unknown";
        String  resetTime       = "Unknown";

        if (isPostReset) {
            // Parse key=value lines written by writePendingRestartFlag()
            try (java.io.BufferedReader br =
                         new java.io.BufferedReader(new java.io.FileReader(pendingFlag))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("initiator=")) {
                        resetInitiator = line.substring("initiator=".length());
                    } else if (line.startsWith("reset_time=")) {
                        resetTime = line.substring("reset_time=".length());
                    }
                }
            } catch (java.io.IOException e) {
                getLogger().warning("[PostRestart] Could not read pending-post-reset.flag: "
                        + e.getMessage());
            }

            pendingFlag.delete();
            getLogger().info("[PostRestart] Post-reset flag detected (initiator=" + resetInitiator
                    + ") — gamerules, difficulty, world border, and spawn will be applied in 2 ticks.");

            // Apply post-reset steps (gamerules, difficulty, world border, spawn, commands)
            final String finalInitiator = resetInitiator;
            getServer().getScheduler().runTaskLater(this, () -> {
                resetManager.applyPostResetSteps();
                getServer().broadcastMessage(
                        org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                configManager.getMsgResetComplete()));
                getLogger().info("[PostRestart] Post-reset apply complete (initiator="
                        + finalInitiator + ").");
            }, 2L);
        }

        // ── Discord startup notification ───────────────────────────────────
        // Sent asynchronously every time onEnable() fires — covers both a
        // post-reset restart and a normal server start.  When it was a
        // post-reset restart, {reason}, {reset_initiator}, and {reset_time}
        // are populated with the actual reset context read from the flag file.
        {
            final String webhookUrl     = configManager.getDiscordWebhookUrl();
            final boolean postReset     = isPostReset;
            final String  finalInit     = resetInitiator;
            final String  finalResetTs  = resetTime;

            if (webhookUrl != null && !webhookUrl.isEmpty()) {
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    String now        = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            .format(new java.util.Date());
                    String serverName = getServer().getName();
                    String reason     = postReset ? "World Reset" : "Normal Start";
                    String initiator  = postReset ? finalInit    : "N/A";
                    String resetTs    = postReset ? finalResetTs : "N/A";

                    String msg = configManager.getDiscordStartupTemplate()
                            .replace("{server}",          serverName)
                            .replace("{time}",            now)
                            .replace("{reason}",          reason)
                            .replace("{reset_initiator}", initiator)
                            .replace("{reset_time}",      resetTs)
                            .replace("\\n",               "\n");

                    resetManager.sendDiscordWebhook(webhookUrl, msg);
                    getLogger().info("[Discord] Startup notification sent (reason=" + reason + ").");
                });
            }
        }

        // Fix 6: clean up stale backups from previous runs on startup
        if (configManager.isBackupEnabled()) {
            resetManager.pruneAllOldBackups();
        }

        // Check for updates asynchronously — registers the join-notification listener
        if (configManager.isUpdateCheckerEnabled()) {
            updateChecker = new UpdateChecker(this);
            getServer().getPluginManager().registerEvents(updateChecker, this);
            updateChecker.check();
        }

        getLogger().info("WorldResetPlugin v" + getDescription().getVersion() + " enabled. Type /worldreset help for commands.");
    }

    @Override
    public void onDisable() {
        if (voteManager != null && voteManager.isVoteActive()) voteManager.cancelVote();
        if (scheduleManager != null) scheduleManager.stop();
        if (resetManager != null && resetManager.isResetPending()) resetManager.cancelReset("ServerShutdown");
        getLogger().info("WorldResetPlugin disabled.");
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public static WorldResetPlugin   getInstance()                  { return instance; }
    public ConfigManager             getConfigManager()             { return configManager; }
    public ResetManager              getResetManager()              { return resetManager; }
    public ScheduleManager           getScheduleManager()           { return scheduleManager; }
    public ServerPropertiesManager   getServerPropertiesManager()   { return serverPropertiesManager; }
    public HistoryManager            getHistoryManager()            { return historyManager; }
    public VoteManager               getVoteManager()               { return voteManager; }
    public UpdateChecker             getUpdateChecker()             { return updateChecker; }
}
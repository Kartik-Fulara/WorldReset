package com.worldreset;

import com.worldreset.commands.ResetCommand;
import com.worldreset.managers.ConfigManager;
import com.worldreset.managers.HistoryManager;
import com.worldreset.managers.ResetManager;
import com.worldreset.managers.ScheduleManager;
import com.worldreset.managers.ServerPropertiesManager;
import com.worldreset.managers.VoteManager;
import org.bukkit.plugin.java.JavaPlugin;

public class WorldResetPlugin extends JavaPlugin {

    private static WorldResetPlugin instance;
    private ConfigManager            configManager;
    private ResetManager             resetManager;
    private ScheduleManager          scheduleManager;
    private ServerPropertiesManager  serverPropertiesManager;
    private HistoryManager           historyManager;
    private VoteManager              voteManager;

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

        // Fix 6: clean up stale backups from previous runs on startup
        if (configManager.isBackupEnabled()) {
            resetManager.pruneAllOldBackups();
        }

        getLogger().info("WorldResetPlugin v1.2.0 enabled. Type /worldreset help for commands.");
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
}
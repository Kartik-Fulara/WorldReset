package com.worldreset.managers;

import com.worldreset.WorldResetPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.util.*;

/**
 * Thin wrapper around config.yml (and server-properties.yml for the patch list).
 *
 * v1.2.0 changes
 * ─────────────────
 *  • server-properties patch configuration moved to its own server-properties.yml
 *    (loaded via serverPropsCfg / serverPropsFile fields below).
 *    All other keys remain in config.yml as before.
 */
public class ConfigManager {

    private final WorldResetPlugin plugin;

    // Secondary config file for server.properties patching
    private FileConfiguration serverPropsCfg;
    private File              serverPropsFile;

    // Advanced config file for world-environments and world-generators
    private FileConfiguration advancedCfg;
    private File              advancedCfgFile;

    public ConfigManager(WorldResetPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Initialise (or re-initialise) the server-properties.yml file.
     * Creates the file with defaults if it does not exist yet.
     * Call this once from WorldResetPlugin.onEnable() after saveDefaultConfig().
     */
    public void initServerPropsFile() {
        serverPropsFile = new File(plugin.getDataFolder(), "server-properties.yml");

        if (!serverPropsFile.exists()) {
            // Copy the bundled default from the JAR; fall back to writing inline.
            try {
                plugin.saveResource("server-properties.yml", false);
            } catch (Exception e) {
                // saveResource throws if the resource is missing — write a minimal default
                writeDefaultServerPropsFile(serverPropsFile);
            }
        }

        serverPropsCfg = YamlConfiguration.loadConfiguration(serverPropsFile);
        initAdvancedFile();
    }

    /**
     * Initialise (or re-initialise) advanced.yml.
     * Creates the file with an empty template if it does not exist yet.
     * Called automatically from {@link #initServerPropsFile()}.
     */
    public void initAdvancedFile() {
        advancedCfgFile = new File(plugin.getDataFolder(), "advanced.yml");

        if (!advancedCfgFile.exists()) {
            try {
                plugin.saveResource("advanced.yml", false);
            } catch (Exception e) {
                writeDefaultAdvancedFile(advancedCfgFile);
            }
        }

        advancedCfg = YamlConfiguration.loadConfiguration(advancedCfgFile);
    }

    /** Re-read advanced.yml from disk. */
    public void reloadAdvancedConfig() {
        if (advancedCfgFile != null) {
            advancedCfg = YamlConfiguration.loadConfiguration(advancedCfgFile);
        }
    }

    /** Re-read server-properties.yml from disk. */
    public void reloadServerPropsConfig() {
        if (serverPropsFile != null) {
            serverPropsCfg = YamlConfiguration.loadConfiguration(serverPropsFile);
        }
    }

    /** Full reload — config.yml + server-properties.yml + advanced.yml. */
    public void reload() {
        plugin.reloadConfig();
        reloadServerPropsConfig();
        reloadAdvancedConfig();
    }

    // ── Convenience shorthand ──────────────────────────────────────────────

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private void save() {
        plugin.saveConfig();
    }

    private void saveAdvanced() {
        if (advancedCfg == null || advancedCfgFile == null) return;
        try {
            advancedCfg.save(advancedCfgFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save advanced.yml: " + e.getMessage());
        }
    }

    private void saveServerProps() {
        if (serverPropsCfg == null || serverPropsFile == null) return;
        try {
            serverPropsCfg.save(serverPropsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save server-properties.yml: " + e.getMessage());
        }
    }

    // ── Reset settings ─────────────────────────────────────────────────────

    public int getCountdown() {
        return cfg().getInt("reset.countdown", 10);
    }

    public void setCountdown(int seconds) {
        cfg().set("reset.countdown", Math.max(0, seconds));
        save();
    }

    public int getBroadcastInterval() {
        return Math.max(1, cfg().getInt("reset.broadcast-interval", 5));
    }

    public void setBroadcastInterval(int seconds) {
        cfg().set("reset.broadcast-interval", Math.max(1, seconds));
        save();
    }

    public String getKickMessage() {
        return cfg().getString("reset.kick-message",
                "World resetting — reconnect in ~90 seconds!");
    }

    public void setKickMessage(String msg) {
        cfg().set("reset.kick-message", msg);
        save();
    }

    // ── Worlds ─────────────────────────────────────────────────────────────

    public List<String> getWorldsToReset() {
        return cfg().getStringList("worlds");
    }

    public void addWorld(String worldName) {
        List<String> list = new ArrayList<>(getWorldsToReset());
        if (!list.contains(worldName)) {
            list.add(worldName);
            cfg().set("worlds", list);
            save();
        }
    }

    public boolean removeWorld(String worldName) {
        List<String> list = new ArrayList<>(getWorldsToReset());
        boolean removed = list.remove(worldName);
        if (removed) {
            cfg().set("worlds", list);
            save();
        }
        return removed;
    }

    // ── World Environments ─────────────────────────────────────────────────

    public String getWorldEnvironment(String worldName) {
        if (advancedCfg == null) return null;
        String key = "world-environments." + worldName;
        return advancedCfg.isSet(key) ? advancedCfg.getString(key) : null;
    }

    public void setWorldEnvironment(String worldName, String environment) {
        if (advancedCfg == null) return;
        advancedCfg.set("world-environments." + worldName,
                environment == null ? null : environment.toUpperCase());
        saveAdvanced();
    }

    public Map<String, String> getAllWorldEnvironments() {
        Map<String, String> map = new LinkedHashMap<>();
        if (advancedCfg == null) return map;
        if (advancedCfg.isConfigurationSection("world-environments")) {
            for (String key : advancedCfg.getConfigurationSection("world-environments").getKeys(false)) {
                map.put(key, advancedCfg.getString("world-environments." + key));
            }
        }
        return map;
    }

    // ── World Generators ───────────────────────────────────────────────────

    public String getWorldGenerator(String worldName) {
        if (advancedCfg == null) return null;
        String key = "world-generators." + worldName;
        if (!advancedCfg.isSet(key)) return null;
        String val = advancedCfg.getString(key, "");
        return (val == null || val.isEmpty()) ? null : val;
    }

    public void setWorldGenerator(String worldName, String generator) {
        if (advancedCfg == null) return;
        advancedCfg.set("world-generators." + worldName,
                (generator == null || generator.isEmpty()) ? null : generator);
        saveAdvanced();
    }

    public Map<String, String> getAllWorldGenerators() {
        Map<String, String> map = new LinkedHashMap<>();
        if (advancedCfg == null) return map;
        if (advancedCfg.isConfigurationSection("world-generators")) {
            for (String key : advancedCfg.getConfigurationSection("world-generators").getKeys(false)) {
                String val = advancedCfg.getString("world-generators." + key, "");
                if (val != null && !val.isEmpty()) {
                    map.put(key, val);
                }
            }
        }
        return map;
    }

    // ── Extra delete paths ─────────────────────────────────────────────────

    public List<String> getExtraDeletePaths() {
        return cfg().getStringList("extra-paths.delete");
    }

    public void addDeletePath(String path) {
        List<String> list = new ArrayList<>(getExtraDeletePaths());
        if (!list.contains(path)) {
            list.add(path);
            cfg().set("extra-paths.delete", list);
            save();
        }
    }

    public boolean removeDeletePath(String path) {
        List<String> list = new ArrayList<>(getExtraDeletePaths());
        boolean removed = list.remove(path);
        if (removed) {
            cfg().set("extra-paths.delete", list);
            save();
        }
        return removed;
    }

    // ── Preserve paths ─────────────────────────────────────────────────────

    public List<String> getPreservePaths() {
        return cfg().getStringList("extra-paths.preserve");
    }

    public void addPreservePath(String path) {
        List<String> list = new ArrayList<>(getPreservePaths());
        if (!list.contains(path)) {
            list.add(path);
            cfg().set("extra-paths.preserve", list);
            save();
        }
    }

    public boolean removePreservePath(String path) {
        List<String> list = new ArrayList<>(getPreservePaths());
        boolean removed = list.remove(path);
        if (removed) {
            cfg().set("extra-paths.preserve", list);
            save();
        }
        return removed;
    }

    // ── Gamerules ──────────────────────────────────────────────────────────

    public Map<String, String> getGamerules() {
        Map<String, String> map = new LinkedHashMap<>();
        if (cfg().isConfigurationSection("gamerules")) {
            for (String key : cfg().getConfigurationSection("gamerules").getKeys(false)) {
                map.put(key, cfg().getString("gamerules." + key, ""));
            }
        }
        return map;
    }

    public void setGamerule(String rule, String value) {
        cfg().set("gamerules." + rule, value);
        save();
    }

    public boolean removeGamerule(String rule) {
        String path = "gamerules." + rule;
        if (cfg().isSet(path)) {
            cfg().set(path, null);
            save();
            return true;
        }
        return false;
    }

    // ── Difficulty ─────────────────────────────────────────────────────────

    public String getDifficulty() {
        return cfg().getString("difficulty", "HARD").toLowerCase();
    }

    public void setDifficulty(String diff) {
        cfg().set("difficulty", diff.toLowerCase());
        save();
    }

    // ── Messages ───────────────────────────────────────────────────────────

    public String getMsgTitle()           { return cfg().getString("messages.title",          "&cWORLD RESETTING"); }
    public String getMsgSubtitle()        { return cfg().getString("messages.subtitle",        "&eNew world in &c{seconds}&e seconds!"); }
    public String getMsgCountdownSay()    { return cfg().getString("messages.countdown-say",   "&c[RESET] &eWorld resetting in &c{seconds}&e seconds. Reconnect soon!"); }
    public String getMsgCountdownStart()  { return cfg().getString("messages.countdown-start", "&c[RESET] &eWorld reset initiated by &f{player}&e. Stand by!"); }
    public String getMsgResetComplete()   { return cfg().getString("messages.reset-complete",  "&a[RESET] &eWorld has been reset! Welcome to the fresh world!"); }
    public String getMsgResetCancelled()  { return cfg().getString("messages.reset-cancelled", "&a[RESET] &eWorld reset has been cancelled."); }

    // ── Restart mode ───────────────────────────────────────────────────────

    public boolean isUseRestart() {
        return cfg().getBoolean("restart.use-restart", false);
    }

    public void setUseRestart(boolean val) {
        cfg().set("restart.use-restart", val);
        save();
    }

    // ── Async deletion ─────────────────────────────────────────────────────

    public boolean isDeleteAsync() {
        return cfg().getBoolean("async.delete-async", true);
    }

    public void setDeleteAsync(boolean val) {
        cfg().set("async.delete-async", val);
        save();
    }

    // ── Backup ─────────────────────────────────────────────────────────────

    public boolean isBackupEnabled()    { return cfg().getBoolean("backup.enabled",   false); }
    public String  getBackupDirectory() { return cfg().getString( "backup.directory", "backups"); }
    public int     getBackupKeep()      { return Math.max(1, cfg().getInt("backup.keep", 5)); }

    public void setBackupEnabled(boolean val)  { cfg().set("backup.enabled",   val); save(); }
    public void setBackupDirectory(String dir) { cfg().set("backup.directory", dir); save(); }
    public void setBackupKeep(int keep)        { cfg().set("backup.keep", Math.max(1, keep)); save(); }

    // ── Schedule ───────────────────────────────────────────────────────────

    public boolean isScheduleEnabled()       { return cfg().getBoolean("schedule.enabled",         false); }
    public String  getScheduleMode()         { return cfg().getString( "schedule.mode",             "interval"); }
    public int     getScheduleIntervalSecs() { return cfg().getInt(    "schedule.interval-seconds", 86400); }
    public String  getScheduleDailyTime()    { return cfg().getString( "schedule.daily-time",       "04:00"); }
    public String  getScheduleCron()         { return cfg().getString( "schedule.cron",             "0 0 * * *"); }

    public void setScheduleEnabled(boolean val)   { cfg().set("schedule.enabled",          val); save(); }
    public void setScheduleMode(String mode)       { cfg().set("schedule.mode",              mode); save(); }
    public void setScheduleIntervalSecs(int secs)  { cfg().set("schedule.interval-seconds",  Math.max(1, secs)); save(); }
    public void setScheduleDailyTime(String time)  { cfg().set("schedule.daily-time",        time); save(); }
    public void setScheduleCron(String cron)       { cfg().set("schedule.cron",              cron); save(); }

    // ── Regions ────────────────────────────────────────────────────────────

    public boolean isRegionsEnabled() { return cfg().getBoolean("regions.enabled", false); }
    public void setRegionsEnabled(boolean val) { cfg().set("regions.enabled", val); save(); }

    public List<Map<?, ?>> getRegionsList() {
        return cfg().getMapList("regions.list");
    }

    public boolean isUseTemplate()           { return cfg().getBoolean("reset.use-template",      false); }
    public String  getTemplateDirectory()    { return cfg().getString( "reset.template-directory", "templates"); }
    public void setUseTemplate(boolean val)  { cfg().set("reset.use-template", val); save(); }
    public void setTemplateDirectory(String dir) { cfg().set("reset.template-directory", dir); save(); }

    /** Safety floor for the schedule interval in seconds. Prevents dangerously short cycles. */
    public int getScheduleMinIntervalSecs() {
        return Math.max(1, cfg().getInt("schedule.min-interval-seconds", 60));
    }

    /**
     * IANA timezone ID used for the daily reset schedule (e.g. "UTC", "America/New_York").
     * Defaults to "UTC" so behaviour is predictable on shared-hosting servers.
     */
    public String getScheduleTimezone() {
        return cfg().getString("schedule.timezone", "UTC");
    }

    // ── Whitelist during reset ─────────────────────────────────────────────

    public boolean isWhitelistDuringReset() {
        return cfg().getBoolean("whitelist-during-reset", false);
    }

    public void setWhitelistDuringReset(boolean val) {
        cfg().set("whitelist-during-reset", val);
        save();
    }

    // ── World Border ───────────────────────────────────────────────────────

    public boolean isWorldBorderEnabled()  { return cfg().getBoolean("world-border.enabled",   false); }
    public double  getWorldBorderSize()    { return cfg().getDouble( "world-border.size",       1000.0); }
    public double  getWorldBorderCenterX() { return cfg().getDouble( "world-border.center-x",   0.0); }
    public double  getWorldBorderCenterZ() { return cfg().getDouble( "world-border.center-z",   0.0); }

    public void setWorldBorderEnabled(boolean val)        { cfg().set("world-border.enabled",  val);  save(); }
    public void setWorldBorderSize(double size)           { cfg().set("world-border.size",     size); save(); }
    public void setWorldBorderCenter(double x, double z) {
        cfg().set("world-border.center-x", x);
        cfg().set("world-border.center-z", z);
        save();
    }

    // ── Spawn ──────────────────────────────────────────────────────────────

    public boolean isSpawnEnabled() { return cfg().getBoolean("spawn.enabled", false); }
    public String  getSpawnWorld()  { return cfg().getString( "spawn.world",   "world"); }
    public double  getSpawnX()      { return cfg().getDouble( "spawn.x",       0.0); }
    public double  getSpawnY()      { return cfg().getDouble( "spawn.y",       64.0); }
    public double  getSpawnZ()      { return cfg().getDouble( "spawn.z",       0.0); }
    public float   getSpawnYaw()    { return (float) cfg().getDouble("spawn.yaw",   0.0); }
    public float   getSpawnPitch()  { return (float) cfg().getDouble("spawn.pitch", 0.0); }

    public void setSpawnEnabled(boolean val)  { cfg().set("spawn.enabled", val); save(); }
    public void setSpawnWorld(String world)   { cfg().set("spawn.world",   world); save(); }
    public void setSpawnLocation(double x, double y, double z, float yaw, float pitch) {
        cfg().set("spawn.x", x); cfg().set("spawn.y", y); cfg().set("spawn.z", z);
        cfg().set("spawn.yaw", yaw); cfg().set("spawn.pitch", pitch);
        save();
    }

    // ── Pre / Post reset commands ──────────────────────────────────────────

    public List<String> getPreResetCommands()  { return cfg().getStringList("pre-reset-commands"); }
    public List<String> getPostResetCommands() { return cfg().getStringList("post-reset-commands"); }

    // ── Preserved Regions (WorldEdit) ──────────────────────────────────────

    public boolean isRegionsEnabled() {
        return cfg().getBoolean("regions.enabled", false);
    }

    public List<Map<?, ?>> getPreservedRegions() {
        return cfg().getMapList("regions.list");
    }

    /**
     * When true, a pre-reset command that returns false or throws will abort the reset.
     * When false (default), failures are logged as warnings and the reset continues.
     */
    public boolean isPreResetCommandsStrict() {
        return cfg().getBoolean("pre-reset-commands-strict", false);
    }

    public void setPreResetCommands(List<String> cmds)  { cfg().set("pre-reset-commands",  cmds); save(); }
    public void setPostResetCommands(List<String> cmds) { cfg().set("post-reset-commands", cmds); save(); }

    // ── Discord Webhook ────────────────────────────────────────────────────

    public String getDiscordWebhookUrl()     { return cfg().getString("notifications.discord-webhook",         ""); }
    public String getDiscordStartMessage()   { return cfg().getString("notifications.discord-start-message",   ""); }
    public String getDiscordCompleteMessage(){ return cfg().getString("notifications.discord-complete-message", ""); }

    /**
     * Returns the rich Discord start-message template.
     * Supports all {placeholder} tokens documented in ResetManager.buildDiscordMessage().
     * Falls back to a concise default if the key is absent from config.
     */
    public String getDiscordStartTemplate() {
        String def =
                "🔄 **World Reset STARTED** — **{server}**\\n" +
                "**Initiated by:** {initiator}\\n" +
                "**Worlds:** {worlds}\\n" +
                "**Mode:** {mode} | **Difficulty:** {difficulty}\\n" +
                "**Seeds:** {seeds}\\n" +
                "**Gamerules:** {gamerules}\\n" +
                "**Player data cleared:** {player_data}\\n" +
                "**Backup:** {backup}\\n" +
                "**Voter list:** {voter_list}\\n" +
                "**Time:** {time}";
        return cfg().getString("notifications.discord-start-template", def);
    }

    /**
     * Returns the rich Discord complete-message template.
     * Supports all {placeholder} tokens documented in ResetManager.buildDiscordMessage().
     */
    public String getDiscordCompleteTemplate() {
        String def =
                "✅ **World Reset COMPLETE** — **{server}**\\n" +
                "**Initiated by:** {initiator}\\n" +
                "**Worlds:** {worlds}\\n" +
                "**Mode:** {mode} | **Duration:** {duration}\\n" +
                "**Worlds detail:** {worlds_detail}\\n" +
                "**Time:** {time}";
        return cfg().getString("notifications.discord-complete-template", def);
    }

    public void setDiscordWebhookUrl(String url) { cfg().set("notifications.discord-webhook", url); save(); }

    /**
     * Returns the Discord startup-notification template.
     * Sent from WorldResetPlugin.onEnable() — both on a post-reset restart
     * and on a normal server start when a webhook is configured.
     *
     * <h3>Available placeholders</h3>
     * <pre>
     *  {server}           — server name
     *  {time}             — date/time of this startup
     *  {reason}           — "World Reset" or "Normal Start"
     *  {reset_initiator}  — player name / "Schedule" / "Vote" that triggered the reset,
     *                       or "N/A" when {reason} is "Normal Start"
     *  {reset_time}       — timestamp when the reset began (from the flag file),
     *                       or "N/A" when {reason} is "Normal Start"
     * </pre>
     */
    public String getDiscordStartupTemplate() {
        String def =
                "🟢 **Server Online** — **{server}**\n" +
                "**Reason:** {reason}\n" +
                "**Reset started by:** {reset_initiator}  |  **Reset began at:** {reset_time}\n" +
                "⏰ **Online at:** {time}";
        return cfg().getString("notifications.discord-startup-template", def);
    }

    /** Seconds-remaining values at which a Discord milestone message should fire. */
    public List<Integer> getDiscordCountdownMilestones() {
        List<?> raw = cfg().getList("notifications.discord-countdown-milestones");
        List<Integer> result = new ArrayList<>();
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof Number) result.add(((Number) o).intValue());
            }
        }
        return result;
    }

    public String getDiscordMilestoneMessage() {
        return cfg().getString("notifications.discord-milestone-message",
                "⏰ World resetting in {seconds} seconds!");
    }

    // ── Cooldown ───────────────────────────────────────────────────────────

    /** Minimum seconds between resets. 0 = disabled. */
    public int getCooldownSeconds() {
        return Math.max(0, cfg().getInt("reset.cooldown-seconds", 0));
    }

    public void setCooldownSeconds(int secs) {
        cfg().set("reset.cooldown-seconds", Math.max(0, secs));
        save();
    }

    // ── ActionBar countdown ────────────────────────────────────────────────

    public boolean isActionbarCountdown() {
        return cfg().getBoolean("reset.actionbar-countdown", true);
    }

    public int getActionbarThreshold() {
        return Math.max(1, cfg().getInt("reset.actionbar-threshold", 30));
    }

    // ── Vote ───────────────────────────────────────────────────────────────

    public boolean isVoteEnabled()         { return cfg().getBoolean("vote.enabled",                false); }
    public int     getVoteRequiredPercent(){ return cfg().getInt(    "vote.required-percent",        60);    }
    public int     getVoteDurationSeconds(){ return cfg().getInt(    "vote.vote-duration-seconds",   60);    }
    public int     getVoteMinPlayers()     { return cfg().getInt(    "vote.min-players",             3);     }

    public void setVoteEnabled(boolean val) { cfg().set("vote.enabled", val); save(); }
    public void setVoteRequiredPercent(int val) { cfg().set("vote.required-percent", val); save(); }
    public void setVoteDurationSeconds(int val) { cfg().set("vote.vote-duration-seconds", val); save(); }
    public void setVoteMinPlayers(int val) { cfg().set("vote.min-players", val); save(); }

    // ── Player Data ────────────────────────────────────────────────────────

    public boolean isPlayerDataEnabled()          { return cfg().getBoolean("player-data.enabled",           false); }
    public boolean isPlayerDataClearInventory()   { return cfg().getBoolean("player-data.clear-inventory",   true);  }
    public boolean isPlayerDataClearEnderChest()  { return cfg().getBoolean("player-data.clear-ender-chest", false); }
    public boolean isPlayerDataClearXp()          { return cfg().getBoolean("player-data.clear-xp",          true);  }
    public boolean isPlayerDataResetHealth()      { return cfg().getBoolean("player-data.reset-health",      true);  }
    public boolean isPlayerDataResetHunger()      { return cfg().getBoolean("player-data.reset-hunger",      true);  }
    public boolean isPlayerDataClearAdvancements(){ return cfg().getBoolean("player-data.clear-advancements", true); }
    public boolean isPlayerDataClearStats()       { return cfg().getBoolean("player-data.clear-stats",       true);  }

    public void setPlayerDataEnabled(boolean val)           { cfg().set("player-data.enabled",           val); save(); }
    public void setPlayerDataClearInventory(boolean val)    { cfg().set("player-data.clear-inventory",   val); save(); }
    public void setPlayerDataClearEnderChest(boolean val)   { cfg().set("player-data.clear-ender-chest", val); save(); }
    public void setPlayerDataClearXp(boolean val)           { cfg().set("player-data.clear-xp",          val); save(); }
    public void setPlayerDataResetHealth(boolean val)       { cfg().set("player-data.reset-health",      val); save(); }
    public void setPlayerDataResetHunger(boolean val)       { cfg().set("player-data.reset-hunger",      val); save(); }
    public void setPlayerDataClearAdvancements(boolean val) { cfg().set("player-data.clear-advancements",val); save(); }
    public void setPlayerDataClearStats(boolean val)        { cfg().set("player-data.clear-stats",       val); save(); }

    // ── Seeds ──────────────────────────────────────────────────────────────

    public Long getSeedForWorld(String worldName) {
        String key = "seeds." + worldName;
        if (!cfg().isSet(key)) return null;
        Object val = cfg().get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(String.valueOf(val)); } catch (NumberFormatException e) { return null; }
    }

    public void setSeedForWorld(String worldName, Long seed) {
        cfg().set("seeds." + worldName, seed);
        save();
    }

    // ── Hardcore ───────────────────────────────────────────────────────────

    public boolean isHardcoreForWorld(String worldName) {
        return cfg().getBoolean("hardcore." + worldName, false);
    }

    public void setHardcoreForWorld(String worldName, boolean hardcore) {
        cfg().set("hardcore." + worldName, hardcore);
        save();
    }

    // ── Server Properties (server-properties.yml) ─────────────────────────

    /**
     * Whether to patch server.properties on reset.
     * Defaults to true so new installs work out of the box.
     */
    public boolean isServerPropertiesEnabled() {
        if (serverPropsCfg == null) return false;
        return serverPropsCfg.getBoolean("enabled", true);
    }

    public void setServerPropertiesEnabled(boolean val) {
        if (serverPropsCfg == null) return;
        serverPropsCfg.set("enabled", val);
        saveServerProps();
    }

    /**
     * Returns the key→value pairs to write into server.properties on reset.
     * Sourced from server-properties.yml, not config.yml.
     */
    public Map<String, String> getServerPropertiesValues() {
        Map<String, String> map = new LinkedHashMap<>();
        if (serverPropsCfg == null) return map;
        if (serverPropsCfg.isConfigurationSection("values")) {
            for (String key : serverPropsCfg.getConfigurationSection("values").getKeys(false)) {
                map.put(key, String.valueOf(serverPropsCfg.get("values." + key)));
            }
        }
        return map;
    }

    public void setServerPropertiesValue(String key, String value) {
        if (serverPropsCfg == null) return;
        serverPropsCfg.set("values." + key, value);
        // Auto-enable patching whenever a value is set so users don't
        // have to remember to run /worldreset serverprops enable manually.
        if (!serverPropsCfg.getBoolean("enabled", true)) {
            serverPropsCfg.set("enabled", true);
        }
        saveServerProps();
    }

    public boolean removeServerPropertiesValue(String key) {
        if (serverPropsCfg == null) return false;
        String path = "values." + key;
        if (serverPropsCfg.isSet(path)) {
            serverPropsCfg.set(path, null);
            saveServerProps();
            return true;
        }
        return false;
    }

    // ── Update checker ────────────────────────────────────────────────────

    /**
     * Whether the startup version-check against GitHub is enabled.
     * Defaults to {@code true} so new installs get update notifications
     * without any extra configuration.
     */
    public boolean isUpdateCheckerEnabled() {
        return cfg().getBoolean("update-checker.enabled", true);
    }

    /**
     * The {@code owner/repo} slug used to build the GitHub Releases API URL.
     * Returns an empty string when not configured, which UpdateChecker treats
     * as "skip the check and log a warning".
     */
    public String getUpdateCheckerRepo() {
        return cfg().getString("update-checker.github-repo", "");
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private void writeDefaultAdvancedFile(File target) {
        try {
            target.getParentFile().mkdirs();
            try (PrintWriter w = new PrintWriter(new FileWriter(target))) {
                w.println("# WorldResetPlugin — advanced.yml");
                w.println("# Custom world environments and generator plugins.");
                w.println("# Reload with: /worldreset reload");
                w.println("world-environments: {}");
                w.println("world-generators: {}");
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not create default advanced.yml: " + e.getMessage());
        }
    }

    private void writeDefaultServerPropsFile(File target) {
        try {
            target.getParentFile().mkdirs();
            try (PrintWriter w = new PrintWriter(new FileWriter(target))) {
                w.println("# WorldResetPlugin — server-properties.yml");
                w.println("# Values written into server.properties on reset (restart mode only).");
                w.println("enabled: true");
                w.println("values:");
                w.println("  level-name: \"world\"");
                w.println("  gamemode: \"survival\"");
                w.println("  difficulty: \"hard\"");
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not create default server-properties.yml: " + e.getMessage());
        }
    }
}
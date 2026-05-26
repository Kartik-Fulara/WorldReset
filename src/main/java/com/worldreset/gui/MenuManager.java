package com.worldreset.gui;

import com.worldreset.WorldResetPlugin;
import com.worldreset.managers.ConfigManager;
import com.worldreset.managers.ResetManager;
import com.worldreset.managers.ScheduleManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.function.Consumer;

public class MenuManager implements Listener {

    private final WorldResetPlugin plugin;

    private static final String TITLE_MAIN     = "§0WorldReset Dashboard";
    private static final String TITLE_RESET    = "§0Reset Settings";
    private static final String TITLE_NOTIF    = "§0Notification Settings";
    private static final String TITLE_VOTE     = "§0Voting Settings";
    private static final String TITLE_SCHED    = "§0Schedule Settings";
    private static final String TITLE_WORLD    = "§0World Settings";
    private static final String TITLE_PDATA    = "§0Player Data Settings";
    private static final String TITLE_BACKUP   = "§0Backup Settings";
    private static final String TITLE_RESTORE  = "§0Restore from Backup";
    private static final String TITLE_VOTE_ACTION = "§0Cast Your Vote";

    // For chat input handling
    private final Map<UUID, Consumer<String>> chatInputs = new HashMap<>();

    public MenuManager(WorldResetPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Menu Openers ───────────────────────────────────────────────────────

    public void openDashboard(Player player) {
        Inventory inv = Bukkit.createInventory(null, 36, TITLE_MAIN);
        ResetManager rm = plugin.getResetManager();
        ConfigManager cfg = plugin.getConfigManager();

        // Row 1: Core Actions
        inv.setItem(10, createItem(Material.GREEN_WOOL, "§a§lStart Reset", 
                "§7Initiate a world reset.", "§7Requires confirmation in chat."));
        
        String status = rm.isResetPending() ? "§c§lPENDING (" + rm.getSecondsLeft() + "s)" : "§a§lIDLE";
        inv.setItem(13, createItem(Material.COMPASS, "§fStatus: " + status, 
                "§7Current reset state.", "§7Click to refresh."));
        
        inv.setItem(16, createItem(Material.RED_WOOL, "§c§lCancel Reset", 
                "§7Abort a pending reset."));

        // Row 2: Sub-Menus
        inv.setItem(19, createItem(Material.COMPARATOR, "§eReset Settings", "§7Configure countdown, cooldown, etc."));
        inv.setItem(20, createItem(Material.PAPER, "§bNotification Settings", "§7Discord Webhooks and templates."));
        inv.setItem(21, createItem(Material.SUNFLOWER, "§6Voting Settings", "§7Community vote-to-reset options."));
        inv.setItem(22, createItem(Material.CLOCK, "§dSchedule Settings", "§7Auto-reset intervals and times."));
        inv.setItem(23, createItem(Material.GRASS_BLOCK, "§aWorld Settings", "§7Worlds, Seeds, and Environments."));
        inv.setItem(24, createItem(Material.PLAYER_HEAD, "§3Player Data Settings", "§7Configure what data is cleared."));
        inv.setItem(25, createItem(Material.CHEST, "§6Backup & Restore", "§7Manage world backups and restorations."));
        inv.setItem(26, createItem(Material.BOOK, "§fHistory", "§7View recent reset logs."));

        // Utility
        inv.setItem(31, createItem(Material.LEVER, "§eReload Plugin", "§7Reload all configurations."));
        
        player.openInventory(inv);
    }

    public void openResetSettings(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_RESET);
        ConfigManager cfg = plugin.getConfigManager();

        inv.setItem(10, createItem(Material.CLOCK, "§eCountdown: §f" + cfg.getCountdown() + "s", "§7Click to change."));
        inv.setItem(11, createItem(Material.REPEATER, "§eBroadcast Interval: §f" + cfg.getBroadcastInterval() + "s", "§7Click to change."));
        inv.setItem(12, createItem(Material.BARRIER, "§eCooldown: §f" + cfg.getCooldownSeconds() + "s", "§7Click to change."));
        inv.setItem(13, createItem(Material.OAK_SIGN, "§eKick Message", "§7Current: §f" + cfg.getKickMessage(), "§7Click to set via chat."));
        inv.setItem(14, createItem(Material.DIAMOND_SWORD, "§eDifficulty: §f" + cfg.getDifficulty().toUpperCase(), "§7Click to cycle."));
        inv.setItem(15, toggleItem(Material.ENDER_PEARL, "§eRestart Mode", cfg.isUseRestart(), "§7Restart server vs live regeneration."));
        inv.setItem(16, toggleItem(Material.FEATHER, "§eAsync Deletion", cfg.isDeleteAsync(), "§7Delete files off the main thread."));

        addBackButton(inv);
        player.openInventory(inv);
    }

    public void openNotificationSettings(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_NOTIF);
        ConfigManager cfg = plugin.getConfigManager();

        String url = cfg.getDiscordWebhookUrl();
        inv.setItem(10, createItem(Material.PAPER, "§bDiscord Webhook URL", 
                url.isEmpty() ? "§cNot set" : "§aSet (hover to see in config)", "§7Click to set via chat."));
        
        inv.setItem(12, createItem(Material.WRITABLE_BOOK, "§bStart Template", "§7Customize reset start message."));
        inv.setItem(13, createItem(Material.WRITABLE_BOOK, "§bComplete Template", "§7Customize reset complete message."));
        inv.setItem(14, createItem(Material.WRITABLE_BOOK, "§bStartup Template", "§7Customize server start message."));

        inv.setItem(16, createItem(Material.BELL, "§bMilestones", "§7Configure countdown warnings.", "§7Set in config.yml."));

        addBackButton(inv);
        player.openInventory(inv);
    }

    public void openVoteSettings(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_VOTE);
        ConfigManager cfg = plugin.getConfigManager();

        inv.setItem(10, toggleItem(Material.SUNFLOWER, "§6Voting Enabled", cfg.isVoteEnabled(), "§7Allow players to start reset votes."));
        inv.setItem(12, createItem(Material.EXPERIENCE_BOTTLE, "§6Required Percent: §f" + cfg.getVoteRequiredPercent() + "%", "§7Click to cycle (50-100%)."));
        inv.setItem(13, createItem(Material.CLOCK, "§6Vote Duration: §f" + cfg.getVoteDurationSeconds() + "s", "§7Click to cycle (30-300s)."));
        inv.setItem(14, createItem(Material.PLAYER_HEAD, "§6Min Players: §f" + cfg.getVoteMinPlayers(), "§7Click to cycle (1-10)."));

        addBackButton(inv);
        player.openInventory(inv);
    }

    public void openScheduleSettings(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_SCHED);
        ConfigManager cfg = plugin.getConfigManager();

        inv.setItem(10, toggleItem(Material.CLOCK, "§dSchedule Enabled", cfg.isScheduleEnabled(), "§7Automatic world resets."));
        inv.setItem(12, createItem(Material.COMPASS, "§dMode: §f" + cfg.getScheduleMode().toUpperCase(), "§7Cycle between INTERVAL and DAILY."));
        
        if ("interval".equalsIgnoreCase(cfg.getScheduleMode())) {
            inv.setItem(14, createItem(Material.CLOCK, "§dInterval: §f" + cfg.getScheduleIntervalSecs() + "s", "§7Click to cycle."));
        } else {
            inv.setItem(14, createItem(Material.OAK_SIGN, "§dDaily Time: §f" + cfg.getScheduleDailyTime(), "§7Set time (HH:MM) via chat."));
        }

        addBackButton(inv);
        player.openInventory(inv);
    }

    public void openWorldSettings(Player player) {
        Inventory inv = Bukkit.createInventory(null, 36, TITLE_WORLD);
        ConfigManager cfg = plugin.getConfigManager();

        List<String> worlds = cfg.getWorldsToReset();
        int slot = 10;
        for (String w : worlds) {
            if (slot > 25) break;
            inv.setItem(slot++, createItem(Material.GRASS_BLOCK, "§a" + w, "§7Click to manage (Not implemented in GUI).", "§7Use /worldreset worlds."));
        }

        inv.setItem(31, createItem(Material.MAP, "§aAdd World", "§7Enter world name in chat."));

        addBackButton(inv);
        player.openInventory(inv);
    }

    public void openPlayerDataSettings(Player player) {
        Inventory inv = Bukkit.createInventory(null, 36, TITLE_PDATA);
        ConfigManager cfg = plugin.getConfigManager();

        inv.setItem(10, toggleItem(Material.PLAYER_HEAD, "§3PlayerData Enabled", cfg.isPlayerDataEnabled(), "§7Reset player stats/inv on reset."));
        inv.setItem(12, toggleItem(Material.CHEST, "§3Clear Inventory", cfg.isPlayerDataClearInventory(), "§7Wipe player inventories."));
        inv.setItem(13, toggleItem(Material.ENDER_CHEST, "§3Clear EnderChest", cfg.isPlayerDataClearEnderChest(), "§7Wipe ender chests."));
        inv.setItem(14, toggleItem(Material.EXPERIENCE_BOTTLE, "§3Clear XP", cfg.isPlayerDataClearXp(), "§7Reset experience to 0."));
        inv.setItem(15, toggleItem(Material.APPLE, "§3Reset Health/Hunger", cfg.isPlayerDataResetHealth(), "§7Restore full HP and Food."));
        inv.setItem(16, toggleItem(Material.BOOK, "§3Clear Advancements", cfg.isPlayerDataClearAdvancements(), "§7Wipe all progress/advancements."));

        addBackButton(inv);
        player.openInventory(inv);
    }

    public void openVoteMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_VOTE_ACTION);

        inv.setItem(11, createItem(Material.LIME_WOOL, "§a§lVOTE YES", "§7Support the world reset."));
        inv.setItem(15, createItem(Material.RED_WOOL, "§c§lVOTE NO", "§7Oppose the world reset."));

        player.openInventory(inv);
    }

    // ── Click Handler ──────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.startsWith("§0")) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || clicked.getType() == Material.AIR) return;

        ConfigManager cfg = plugin.getConfigManager();

        if (title.equals(TITLE_MAIN)) {
            handleMainClick(player, event.getRawSlot());
        } else if (title.equals(TITLE_RESET)) {
            handleResetClick(player, event.getRawSlot(), cfg);
        } else if (title.equals(TITLE_NOTIF)) {
            handleNotifClick(player, event.getRawSlot(), cfg);
        } else if (title.equals(TITLE_VOTE)) {
            handleVoteClick(player, event.getRawSlot(), cfg);
        } else if (title.equals(TITLE_SCHED)) {
            handleSchedClick(player, event.getRawSlot(), cfg);
        } else if (title.equals(TITLE_PDATA)) {
            handlePDataClick(player, event.getRawSlot(), cfg);
        } else if (title.equals(TITLE_WORLD)) {
            handleWorldClick(player, event.getRawSlot(), cfg);
        } else if (title.equals(TITLE_BACKUP)) {
            handleBackupClick(player, event.getRawSlot(), cfg);
        } else if (title.equals(TITLE_RESTORE)) {
            handleRestoreClick(player, clicked, cfg);
        } else if (title.equals(TITLE_VOTE_ACTION)) {
            handleVoteActionClick(player, clicked);
        }

        // Global back button (slot 27 or 35 depending on size)
        if (event.getRawSlot() == event.getInventory().getSize() - 1 && clicked.getType() == Material.ARROW) {
            openDashboard(player);
        }
    }

    private void handleMainClick(Player player, int slot) {
        ResetManager rm = plugin.getResetManager();
        switch (slot) {
            case 10: 
                rm.handleResetInitiation(player, false, false);
                player.closeInventory(); 
                break;
            case 13: openDashboard(player); break;
            case 16: 
                if (rm.isResetPending()) {
                    rm.cancelReset(player.getName());
                    player.sendMessage("§a[WorldReset] Reset cancelled.");
                } else {
                    player.sendMessage("§c[WorldReset] No reset is currently pending.");
                }
                player.closeInventory(); 
                break;
            case 19: openResetSettings(player); break;
            case 20: openNotificationSettings(player); break;
            case 21: openVoteSettings(player); break;
            case 22: openScheduleSettings(player); break;
            case 23: openWorldSettings(player); break;
            case 24: openPlayerDataSettings(player); break;
            case 25: openBackupSettings(player); break;
            case 26: plugin.getHistoryManager().showHistory(player, 10); player.closeInventory(); break;
            case 31: 
                plugin.getConfigManager().reload();
                plugin.getScheduleManager().restart();
                plugin.getServerPropertiesManager().reload();
                player.sendMessage("§a[WorldReset] Configurations reloaded.");
                player.closeInventory(); 
                break;
        }
    }

    private void handleResetClick(Player player, int slot, ConfigManager cfg) {
        switch (slot) {
            case 10: cfg.setCountdown(cfg.getCountdown() == 10 ? 30 : (cfg.getCountdown() == 30 ? 60 : (cfg.getCountdown() == 60 ? 0 : 10))); break;
            case 11: cfg.setBroadcastInterval(cfg.getBroadcastInterval() == 5 ? 10 : (cfg.getBroadcastInterval() == 10 ? 30 : 5)); break;
            case 12: cfg.setCooldownSeconds(cfg.getCooldownSeconds() == 0 ? 300 : (cfg.getCooldownSeconds() == 300 ? 1800 : (cfg.getCooldownSeconds() == 1800 ? 3600 : 0))); break;
            case 13: 
                player.sendMessage("§e[WorldReset] Enter new kick message in chat (type 'cancel' to abort):");
                requestChatInput(player, msg -> {
                    cfg.setKickMessage(msg);
                    player.sendMessage("§aKick message updated.");
                    openResetSettings(player);
                });
                player.closeInventory();
                return;
            case 14:
                String next = "HARD";
                if (cfg.getDifficulty().equalsIgnoreCase("HARD")) next = "PEACEFUL";
                else if (cfg.getDifficulty().equalsIgnoreCase("PEACEFUL")) next = "EASY";
                else if (cfg.getDifficulty().equalsIgnoreCase("EASY")) next = "NORMAL";
                cfg.setDifficulty(next);
                break;
            case 15: cfg.setUseRestart(!cfg.isUseRestart()); break;
            case 16: cfg.setDeleteAsync(!cfg.isDeleteAsync()); break;
        }
        openResetSettings(player);
    }

    private void handleNotifClick(Player player, int slot, ConfigManager cfg) {
        switch (slot) {
            case 10:
                player.sendMessage("§e[WorldReset] Paste Discord Webhook URL in chat (type 'cancel' to abort):");
                requestChatInput(player, url -> {
                    if (!url.startsWith("https://discord.com/api/webhooks/")) {
                        player.sendMessage("§cInvalid Discord Webhook URL.");
                    } else {
                        cfg.setDiscordWebhookUrl(url);
                        player.sendMessage("§aWebhook URL updated.");
                    }
                    openNotificationSettings(player);
                });
                player.closeInventory();
                break;
            case 12: player.sendMessage("§7Use config.yml to edit complex templates."); break;
            case 13: player.sendMessage("§7Use config.yml to edit complex templates."); break;
            case 14: player.sendMessage("§7Use config.yml to edit complex templates."); break;
        }
    }

    private void handleVoteClick(Player player, int slot, ConfigManager cfg) {
        switch (slot) {
            case 10: cfg.setVoteEnabled(!cfg.isVoteEnabled()); break;
            case 12: cfg.setVoteRequiredPercent(cfg.getVoteRequiredPercent() >= 90 ? 50 : cfg.getVoteRequiredPercent() + 10); break;
            case 13: cfg.setVoteDurationSeconds(cfg.getVoteDurationSeconds() >= 120 ? 30 : cfg.getVoteDurationSeconds() + 30); break;
            case 14: cfg.setVoteMinPlayers(cfg.getVoteMinPlayers() >= 10 ? 1 : cfg.getVoteMinPlayers() + 1); break;
        }
        openVoteSettings(player);
    }

    private void handleSchedClick(Player player, int slot, ConfigManager cfg) {
        switch (slot) {
            case 10: cfg.setScheduleEnabled(!cfg.isScheduleEnabled()); plugin.getScheduleManager().restart(); break;
            case 12: cfg.setScheduleMode(cfg.getScheduleMode().equalsIgnoreCase("interval") ? "daily" : "interval"); plugin.getScheduleManager().restart(); break;
            case 14:
                if (cfg.getScheduleMode().equalsIgnoreCase("interval")) {
                    cfg.setScheduleIntervalSecs(cfg.getScheduleIntervalSecs() >= 86400 ? 3600 : cfg.getScheduleIntervalSecs() + 3600);
                    plugin.getScheduleManager().restart();
                } else {
                    player.sendMessage("§e[WorldReset] Enter daily time in HH:MM format (e.g. 04:00):");
                    requestChatInput(player, time -> {
                        if (time.matches("\\d{1,2}:\\d{2}")) {
                            cfg.setScheduleDailyTime(time);
                            player.sendMessage("§aDaily reset time set to " + time);
                            plugin.getScheduleManager().restart();
                        } else {
                            player.sendMessage("§cInvalid time format.");
                        }
                        openScheduleSettings(player);
                    });
                    player.closeInventory();
                    return;
                }
                break;
        }
        openScheduleSettings(player);
    }

    private void handlePDataClick(Player player, int slot, ConfigManager cfg) {
        switch (slot) {
            case 10: cfg.setPlayerDataEnabled(!cfg.isPlayerDataEnabled()); break;
            case 12: cfg.setPlayerDataClearInventory(!cfg.isPlayerDataClearInventory()); break;
            case 13: cfg.setPlayerDataClearEnderChest(!cfg.isPlayerDataClearEnderChest()); break;
            case 14: cfg.setPlayerDataClearXp(!cfg.isPlayerDataClearXp()); break;
            case 15: cfg.setPlayerDataResetHealth(!cfg.isPlayerDataResetHealth()); break;
            case 16: cfg.setPlayerDataClearAdvancements(!cfg.isPlayerDataClearAdvancements()); break;
        }
        openPlayerDataSettings(player);
    }

    private void handleWorldClick(Player player, int slot, ConfigManager cfg) {
        if (slot == 31) {
            player.sendMessage("§e[WorldReset] Enter name of world to add to reset list:");
            requestChatInput(player, worldName -> {
                cfg.addWorld(worldName);
                player.sendMessage("§aAdded '" + worldName + "' to the reset list.");
                openWorldSettings(player);
            });
            player.closeInventory();
        }
    }

    private void handleVoteActionClick(Player player, ItemStack clicked) {
        if (clicked == null) return;
        if (clicked.getType() == Material.LIME_WOOL) {
            player.performCommand("resetvote yes");
        } else if (clicked.getType() == Material.RED_WOOL) {
            player.performCommand("resetvote no");
        }
        player.closeInventory();
    }

    public void openBackupSettings(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_BACKUP);
        ConfigManager cfg = plugin.getConfigManager();

        inv.setItem(10, toggleItem(Material.CHEST, "§6Backup Enabled", cfg.isBackupEnabled(), "§7ZIP worlds before each reset."));
        inv.setItem(12, createItem(Material.IRON_INGOT, "§6Keep Count: §f" + cfg.getBackupKeep(), "§7Backups to keep per world.", "§7Click to cycle (1-20)."));
        inv.setItem(13, createItem(Material.BOOK, "§6Directory: §f" + cfg.getBackupDirectory(), "§7Click to set via chat."));

        inv.setItem(15, createItem(Material.ANVIL, "§aManual Backup Now", "§7Immediately ZIP all reset worlds."));
        inv.setItem(16, createItem(Material.NETHER_STAR, "§c§lRestore from Backup", "§7List and extract world archives.", "§c⚠ DESTRUCTIVE ACTION"));

        addBackButton(inv);
        player.openInventory(inv);
    }

    public void openRestoreMenu(Player player) {
        List<File> backups = plugin.getResetManager().getAvailableBackups();
        // Dynamic size based on backup count
        int size = Math.max(27, Math.min(54, ((backups.size() / 9) + 2) * 9));
        Inventory inv = Bukkit.createInventory(null, size, TITLE_RESTORE);

        int slot = 0;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (File f : backups) {
            if (slot >= size - 9) break;
            long sizeMb = f.length() / 1_048_576L;
            String date = sdf.format(new Date(f.lastModified()));

            inv.setItem(slot++, createItem(Material.PAPER, "§b" + f.getName(),
                    "§7Size: §f" + sizeMb + " MB",
                    "§7Date: §f" + date,
                    "", "§cClick to RESTORE this backup.", "§c(Requires chat confirmation)"));
        }

        inv.setItem(size - 1, createItem(Material.ARROW, "§7Back to Backup Settings"));
        player.openInventory(inv);
    }

    private void handleBackupClick(Player player, int slot, ConfigManager cfg) {
        switch (slot) {
            case 10: cfg.setBackupEnabled(!cfg.isBackupEnabled()); break;
            case 12:
                int keep = cfg.getBackupKeep();
                cfg.setBackupKeep(keep >= 20 ? 1 : keep + 1);
                break;
            case 13:
                player.sendMessage("§e[WorldReset] Enter new backup directory (type 'cancel' to abort):");
                requestChatInput(player, dir -> {
                    cfg.setBackupDirectory(dir);
                    player.sendMessage("§aBackup directory updated.");
                    openBackupSettings(player);
                });
                player.closeInventory();
                return;
            case 15:
                player.sendMessage("§a[WorldReset] Starting manual backup...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    plugin.getResetManager().runManualBackup();
                    player.sendMessage("§a[WorldReset] Manual backup complete.");
                });
                player.closeInventory();
                return;
            case 16: openRestoreMenu(player); return;
        }
        openBackupSettings(player);
    }

    private void handleRestoreClick(Player player, ItemStack clicked, ConfigManager cfg) {
        if (clicked == null || clicked.getType() != Material.PAPER) return;
        String zipName = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getDisplayName());

        player.sendMessage("§c§l⚠ WARNING: §eRestoring '" + zipName + "' will DELETE all current worlds!");
        player.sendMessage("§7Type §fconfirm §7in chat to proceed, or §fcancel §7to abort.");

        requestChatInput(player, input -> {
            if (input.equalsIgnoreCase("confirm")) {
                plugin.getResetManager().restoreBackup(player.getName(), zipName);
            } else {
                player.sendMessage("§cRestoration aborted.");
            }
        });
        player.closeInventory();
    }

    // ── Chat Input Logic ───────────────────────────────────────────────────

    private void requestChatInput(Player player, Consumer<String> callback) {
        chatInputs.put(player.getUniqueId(), callback);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Consumer<String> callback = chatInputs.remove(player.getUniqueId());
        
        if (callback != null) {
            event.setCancelled(true);
            String msg = event.getMessage();
            
            if (msg.equalsIgnoreCase("cancel")) {
                player.sendMessage("§cInput cancelled.");
                return;
            }
            
            // Re-sync to main thread for API calls
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(msg));
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> loreList = new ArrayList<>(Arrays.asList(lore));
            meta.setLore(loreList);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack toggleItem(Material material, String name, boolean enabled, String... lore) {
        String state = enabled ? "§a§lENABLED" : "§c§lDISABLED";
        ItemStack item = createItem(material, name + ": " + state, lore);
        if (enabled) {
            // Add enchantment glow for enabled items
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private void addBackButton(Inventory inv) {
        inv.setItem(inv.getSize() - 1, createItem(Material.ARROW, "§7Back to Dashboard"));
    }
}
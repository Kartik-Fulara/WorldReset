package com.worldreset.commands;

import com.worldreset.WorldResetPlugin;
import com.worldreset.managers.ConfigManager;
import com.worldreset.managers.ResetManager;
import com.worldreset.managers.ScheduleManager;
import com.worldreset.managers.ServerPropertiesManager;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /worldreset — single entry point for all plugin sub-commands.
 *
 * Changes in this version
 * ────────────────────────
 *  FIX-C  handleStart: --confirm bypass patched.
 *         Passing --confirm without a prior /worldreset start used to skip the
 *         safety check entirely. Now it validates that a pending entry exists
 *         and hasn't expired before proceeding.
 *
 *  FIX-D  handleGamerule: "hardcore" intercepted as a synthetic gamerule.
 *         /worldreset gamerule hardcore true|false sets hardcore for ALL worlds
 *         in the reset list via cfg.setHardcoreForWorld(). It is displayed at
 *         the top of the gamerule list. /worldreset gamerule remove hardcore
 *         resets all worlds to hardcore=false.
 *
 *  NEW    handleProps — /worldreset props (alias: p, prop)
 *         A completely separate sub-command covering ALL 54 standard Minecraft
 *         server.properties keys, grouped by category, with live values, patch
 *         status, [Set] / [X] click buttons and full tab-completion with value
 *         hints. Uses the same ConfigManager / ServerPropertiesManager backend
 *         as serverprops but with a cleaner UX.
 *
 * Sub-command summary (full)
 * ───────────────────────────
 *  (original sub-commands unchanged — see original file header for the full list)
 *
 *  PROPS  (new — server.properties patch editor, all 54 keys)
 *    props                              Show all keys grouped by category
 *    props <key>                        Show live + patch value for one key
 *    props <key> <value>                Set a patch (any of 54 standard keys)
 *    props remove <key>                 Remove a patch
 *    props enable / disable             Toggle patching
 *    props reload                       Re-read server.properties from disk
 */
public class ResetCommand implements CommandExecutor, TabCompleter {

    private static final String PERM              = "worldreset.admin";
    private static final long   CONFIRM_EXPIRE_MS = 30_000L;

    private final WorldResetPlugin plugin;

    /** Pending confirmation state: key = player UUID / "CONSOLE", value = timestamp. */
    private final Map<String, Long> pendingConfirm = new HashMap<>();

    public ResetCommand(WorldResetPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Helpers for per-sub-command permission checks ─────────────────────

    private boolean hasPerm(CommandSender sender, String subPerm) {
        return sender.hasPermission(PERM) || sender.hasPermission(subPerm);
    }

    private boolean denyPerm(CommandSender sender) {
        msg(sender, red("You don't have permission to use this command."));
        return false;
    }

    // ── Command dispatch ───────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        if (!sender.hasPermission(PERM) && !sender.hasPermission("worldreset.use")
                && !sender.isOp()) {
            msg(sender, red("You don't have permission to use this command."));
            return true;
        }

        if (args.length == 0) { sendHelp(sender, 1); return true; }

        switch (args[0].toLowerCase()) {
            case "help": {
                int page = 1;
                if (args.length >= 2) {
                    try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                }
                sendHelp(sender, page);
                break;
            }
            case "s":
            case "start":
                if (!hasPerm(sender, "worldreset.start")) { denyPerm(sender); break; }
                handleStart(sender, args);
                break;
            case "c":
            case "cancel":
                if (!hasPerm(sender, "worldreset.cancel")) { denyPerm(sender); break; }
                handleCancel(sender);
                break;
            case "st":
            case "status":
                if (!hasPerm(sender, "worldreset.status")) { denyPerm(sender); break; }
                handleStatus(sender);
                break;
            case "r":
            case "rl":
            case "reload":
                if (!hasPerm(sender, "worldreset.reload")) { denyPerm(sender); break; }
                handleReload(sender);
                break;
            case "sched":
            case "schedule":
                if (!hasPerm(sender, "worldreset.schedule")) { denyPerm(sender); break; }
                handleSchedule(sender, args);
                break;
            case "bk":
            case "backup":
                if (!hasPerm(sender, "worldreset.backup")) { denyPerm(sender); break; }
                handleBackup(sender, args);
                break;
            case "hist":
            case "history":
                if (!hasPerm(sender, "worldreset.history")) { denyPerm(sender); break; }
                handleHistory(sender, args);
                break;
            case "cfg":
            case "config":      handleConfig(sender, args);          break;
            case "w":
            case "worlds":      handleWorlds(sender, args);          break;
            case "seed":        handleSeed(sender, args);            break;
            case "hardcore":    handleHardcore(sender, args);        break;
            case "delete":      handleDelete(sender, args);          break;
            case "preserve":    handlePreserve(sender, args);        break;
            case "gr":
            case "gamerule":    handleGamerule(sender, args);        break;
            case "gen":
            case "generator":   handleGenerator(sender, args);       break;
            case "env":
            case "environment": handleEnvironment(sender, args);     break;
            case "sp":
            case "serverprops": handleServerProps(sender, args);     break;
            // ── NEW: direct server-properties editor ─────────────────────
            case "p":
            case "prop":
            case "props":       handleProps(sender, args);           break;
            default:
                msg(sender, red("Unknown sub-command. Type /worldreset help to see all commands."));
        }
        return true;
    }

    // ── start (FIX-C: --confirm bypass patched) ───────────────────────────

    private void handleStart(CommandSender sender, String[] args) {
        ResetManager rm = plugin.getResetManager();
        if (rm.isResetPending()) {
            msg(sender, yellow("A reset is already in progress. Use /worldreset cancel to stop it."));
            return;
        }

        boolean now     = hasFlag(args, "--now");
        boolean confirm = hasFlag(args, "--confirm");

        String senderKey = sender instanceof Player
                ? ((Player) sender).getUniqueId().toString()
                : "CONSOLE";

        if (confirm) {
            // FIX-C: Validate a pending confirmation actually exists and hasn't expired.
            // Previously, --confirm with no prior command bypassed the safety check entirely.
            Long prev  = pendingConfirm.get(senderKey);
            long nowMs = System.currentTimeMillis();
            if (prev == null || nowMs - prev > CONFIRM_EXPIRE_MS) {
                msg(sender, red("No pending confirmation — run /worldreset start first, then confirm within 30s."));
                pendingConfirm.remove(senderKey);
                return;
            }
            pendingConfirm.remove(senderKey);
        } else {
            long nowMs = System.currentTimeMillis();
            Long prev  = pendingConfirm.get(senderKey);

            if (prev == null || nowMs - prev > CONFIRM_EXPIRE_MS) {
                if (prev != null && nowMs - prev > CONFIRM_EXPIRE_MS) {
                    msg(sender, red("[RESET] Your confirmation expired. Run the command again to confirm."));
                }
                pendingConfirm.put(senderKey, nowMs);
                msg(sender, red("⚠  WARNING: This will permanently delete and regenerate the world!"));
                msg(sender, yellow("Run the same command again within 30 seconds to confirm:"));
                msg(sender, aqua("  " + (now ? "/worldreset start --now --confirm" : "/worldreset start --confirm")));
                return;
            }
            pendingConfirm.remove(senderKey);
        }

        String initiator = sender instanceof Player ? sender.getName() : "Console";
        rm.startReset(initiator, now);

        if (now) {
            msg(sender, green("Reset triggered immediately."));
        } else {
            int secs = plugin.getConfigManager().getCountdown();
            msg(sender, green("Reset countdown started (" + secs + "s)."));
        }
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    private void handleCancel(CommandSender sender) {
        ResetManager rm = plugin.getResetManager();
        if (!rm.isResetPending()) {
            msg(sender, yellow("No reset is currently pending."));
            return;
        }
        String cancellerName = sender instanceof Player ? sender.getName() : "Console";
        rm.cancelReset(cancellerName);
        msg(sender, green("Reset cancelled."));
    }

    // ── status ─────────────────────────────────────────────────────────────

    private void handleStatus(CommandSender sender) {
        ConfigManager cfg = plugin.getConfigManager();
        ResetManager  rm  = plugin.getResetManager();

        sender.sendMessage(header("WorldReset — Status"));

        sender.sendMessage(section("Reset State"));
        sender.sendMessage(kv("Status",
                rm.isResetPending()
                        ? red("PENDING — " + rm.getSecondsLeft() + "s remaining")
                        : green("Idle")));
        sender.sendMessage(kv("Countdown",            cfg.getCountdown() + "s"));
        sender.sendMessage(kv("Broadcast interval",   cfg.getBroadcastInterval() + "s"));
        sender.sendMessage(kv("Difficulty",           cfg.getDifficulty()));
        sender.sendMessage(kv("Use restart",          String.valueOf(cfg.isUseRestart())));
        sender.sendMessage(kv("Delete async",         String.valueOf(cfg.isDeleteAsync())));
        sender.sendMessage(kv("Whitelist during reset", String.valueOf(cfg.isWhitelistDuringReset())));

        ScheduleManager sm = plugin.getScheduleManager();
        sender.sendMessage(section("Schedule"));
        sender.sendMessage(kv("Enabled",    bool(cfg.isScheduleEnabled())));
        sender.sendMessage(kv("Running",    bool(sm.isRunning())));
        sender.sendMessage(kv("Mode",       cfg.getScheduleMode()));
        if ("interval".equalsIgnoreCase(cfg.getScheduleMode()))
            sender.sendMessage(kv("Interval",   cfg.getScheduleIntervalSecs() + "s"));
        else
            sender.sendMessage(kv("Daily time", cfg.getScheduleDailyTime()));

        sender.sendMessage(section("Backup"));
        sender.sendMessage(kv("Enabled",    bool(cfg.isBackupEnabled())));
        sender.sendMessage(kv("Directory",  cfg.getBackupDirectory()));
        sender.sendMessage(kv("Keep",       String.valueOf(cfg.getBackupKeep())));

        sender.sendMessage(section("Server Properties (server-properties.yml)"));
        sender.sendMessage(kv("Patching enabled", bool(cfg.isServerPropertiesEnabled())));
        Map<String, String> spVals = cfg.getServerPropertiesValues();
        if (spVals.isEmpty()) {
            sender.sendMessage("  " + gray("(no values configured)"));
        } else {
            spVals.forEach((k, v) -> sender.sendMessage("  " + aqua(k) + gray("=") + white(v)));
        }
        if (!cfg.isUseRestart()) {
            sender.sendMessage("  " + yellow("⚠ use-restart=false — patches only apply in restart mode."));
        }

        sender.sendMessage(section("Worlds"));
        List<String> worldsToReset = cfg.getWorldsToReset();
        if (worldsToReset.isEmpty()) {
            sender.sendMessage(yellow("  (no worlds configured)"));
        } else {
            for (String worldName : worldsToReset) {
                World world = Bukkit.getWorld(worldName);
                sender.sendMessage("  " + gold("▸ " + worldName));
                if (world == null) {
                    sender.sendMessage("    " + kv("Loaded", red("No")));
                    sender.sendMessage("    " + kv("Environment", inferEnvironmentLabel(worldName)));
                } else {
                    sender.sendMessage("    " + kv("Loaded",      green("Yes")));
                    sender.sendMessage("    " + kv("Environment", friendlyEnvironment(world.getEnvironment())));
                    sender.sendMessage("    " + kv("Players",     String.valueOf(world.getPlayers().size())));
                    try {
                        sender.sendMessage("    " + kv("Hardcore", world.isHardcore() ? red("YES") : green("No")));
                    } catch (NoSuchMethodError ignored) {}
                }
                Long locked = cfg.getSeedForWorld(worldName);
                sender.sendMessage("    " + kv("Seed after reset",
                        locked != null ? aqua("LOCKED → " + locked) : yellow("random")));
                sender.sendMessage("    " + kv("Hardcore after reset",
                        cfg.isHardcoreForWorld(worldName) ? red("ON") : green("OFF")));
            }
        }

        sender.sendMessage(section("Gamerules"));
        Map<String, String> rules = cfg.getGamerules();
        if (rules.isEmpty()) sender.sendMessage(yellow("  (none)"));
        else rules.forEach((k, v) -> sender.sendMessage("  " + aqua(k) + gray("=") + white(v)));

        sender.sendMessage(gray("  Run /worldreset help for the full command reference."));
    }

    // ── history ────────────────────────────────────────────────────────────

    private void handleHistory(CommandSender sender, String[] args) {
        int n = 10;
        if (args.length >= 2) {
            try {
                n = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                msg(sender, red("Usage: /worldreset history [n]  — n must be a number."));
                return;
            }
        }
        plugin.getHistoryManager().showHistory(sender, n);
    }

    // ── reload ─────────────────────────────────────────────────────────────

    private void handleReload(CommandSender sender) {
        if (plugin.getResetManager().isResetPending()) {
            msg(sender, red("Cannot reload while a reset countdown is in progress."));
            msg(sender, gray("  Use /worldreset cancel first, then reload."));
            return;
        }
        plugin.getConfigManager().reload();
        plugin.getScheduleManager().restart();
        msg(sender, green("config.yml, server-properties.yml, and advanced.yml reloaded. Schedule restarted."));
    }

    // ── config ─────────────────────────────────────────────────────────────

    private void handleConfig(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(header("Config Keys"));
            sender.sendMessage(kv("countdown",              plugin.getConfigManager().getCountdown() + "s"));
            sender.sendMessage(kv("interval",               plugin.getConfigManager().getBroadcastInterval() + "s"));
            sender.sendMessage(kv("kick-msg",               plugin.getConfigManager().getKickMessage()));
            sender.sendMessage(kv("difficulty",             plugin.getConfigManager().getDifficulty()));
            sender.sendMessage(kv("use-restart",            String.valueOf(plugin.getConfigManager().isUseRestart())));
            sender.sendMessage(kv("delete-async",           String.valueOf(plugin.getConfigManager().isDeleteAsync())));
            sender.sendMessage(kv("whitelist-during-reset", String.valueOf(plugin.getConfigManager().isWhitelistDuringReset())));
            sender.sendMessage(gray("  Usage: /worldreset config <key> <value>"));
            return;
        }

        ConfigManager cfg   = plugin.getConfigManager();
        String        key   = args[1].toLowerCase();
        String        value = joinFrom(args, 2);

        switch (key) {
            case "countdown":
                try {
                    int s = Integer.parseInt(value);
                    cfg.setCountdown(s);
                    msg(sender, green("Countdown set to " + s + "s."));
                } catch (NumberFormatException e) {
                    msg(sender, red("Must be a number (seconds)."));
                }
                break;
            case "interval":
                try {
                    int s = Integer.parseInt(value);
                    cfg.setBroadcastInterval(s);
                    msg(sender, green("Broadcast interval set to " + s + "s."));
                } catch (NumberFormatException e) {
                    msg(sender, red("Must be a number (seconds)."));
                }
                break;
            case "kick-msg":
                cfg.setKickMessage(value);
                msg(sender, green("Kick message updated."));
                break;
            case "difficulty":
                String diff = value.toUpperCase();
                if (!diff.matches("PEACEFUL|EASY|NORMAL|HARD")) {
                    msg(sender, red("Valid values: PEACEFUL, EASY, NORMAL, HARD"));
                    break;
                }
                cfg.setDifficulty(diff);
                msg(sender, green("Difficulty set to " + diff + "."));
                break;
            case "use-restart":
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    msg(sender, red("Value must be true or false."));
                    break;
                }
                cfg.setUseRestart(Boolean.parseBoolean(value));
                msg(sender, green("use-restart set to " + value + "."));
                break;
            case "delete-async":
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    msg(sender, red("Value must be true or false."));
                    break;
                }
                cfg.setDeleteAsync(Boolean.parseBoolean(value));
                msg(sender, green("delete-async set to " + value + "."));
                break;
            case "whitelist-during-reset":
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    msg(sender, red("Value must be true or false."));
                    break;
                }
                cfg.setWhitelistDuringReset(Boolean.parseBoolean(value));
                msg(sender, green("whitelist-during-reset set to " + value + "."));
                break;
            default:
                msg(sender, red("Unknown key '" + key + "'. Run /worldreset config to see all keys."));
        }
    }

    // ── worlds ─────────────────────────────────────────────────────────────

    private void handleWorlds(CommandSender sender, String[] args) {
        ConfigManager cfg = plugin.getConfigManager();

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            TextComponent hdr = new TextComponent(header("Worlds Deleted & Regenerated on Reset"));
            TextComponent addBtn = new TextComponent(" §8[§aAdd§8]");
            addBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/worldreset worlds add "));
            addBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§7Add a world to the reset list")));
            hdr.addExtra(addBtn);
            sendComponent(sender, new BaseComponent[]{hdr},
                    header("Worlds Deleted & Regenerated on Reset") + "  [Add: /worldreset worlds add ]");
            List<String> worlds = cfg.getWorldsToReset();
            if (worlds.isEmpty()) {
                sender.sendMessage(yellow("  (none configured)"));
            } else {
                for (String w : worlds) {
                    World live = Bukkit.getWorld(w);
                    String label = "  §b" + w + (live != null ? " §a[loaded]" : " §7[not loaded]");
                    sendComponent(sender,
                            clickableLine(label, "Remove", "c",
                                    "/worldreset worlds remove " + w,
                                    "Click to remove " + w + " from the reset list"),
                            label + "  [Remove: /worldreset worlds remove " + w + "]");
                }
            }
            return;
        }

        switch (args[1].toLowerCase()) {
            case "add":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset worlds add <name>")); break; }
                cfg.addWorld(args[2]);
                msg(sender, green("Added '" + args[2] + "' to the reset list."));
                break;
            case "remove":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset worlds remove <name>")); break; }
                if (cfg.removeWorld(args[2])) msg(sender, green("Removed '" + args[2] + "'."));
                else msg(sender, yellow("'" + args[2] + "' was not in the list."));
                break;
            default:
                msg(sender, red("Usage: /worldreset worlds [add|remove] <name>"));
        }
    }

    // ── seed ───────────────────────────────────────────────────────────────

    private void handleSeed(CommandSender sender, String[] args) {
        ConfigManager cfg = plugin.getConfigManager();

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(header("World Seeds After Reset"));
            sender.sendMessage(gray("  All worlds start with the same seed because Spigot creates them"));
            sender.sendMessage(gray("  that way at first launch. Each world has an independent seed and"));
            sender.sendMessage(gray("  can be locked or randomised separately after reset."));
            cfg.getWorldsToReset().forEach(wName -> {
                Long locked = cfg.getSeedForWorld(wName);
                World live  = Bukkit.getWorld(wName);
                sender.sendMessage("  " + gold("▸ " + wName));

                if (live != null) {
                    String seedStr   = Long.toString(live.getSeed());
                    String seedLabel = "    §eCurrent seed§7: §f" + seedStr;
                    TextComponent seedLine = new TextComponent(seedLabel);
                    TextComponent copyBtn  = new TextComponent(" §8[§bCopy§8]");
                    copyBtn.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, seedStr));
                    copyBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new Text("§7Click to copy seed to clipboard")));
                    seedLine.addExtra(copyBtn);
                    sendComponent(sender, new BaseComponent[]{seedLine},
                            seedLabel + "  [Copy seed: " + seedStr + "]");

                    if (locked != null) {
                        String lockedLabel = "    §eAfter reset§7: §bLOCKED → §f" + locked;
                        TextComponent lockedLine = new TextComponent(lockedLabel);
                        TextComponent unlockBtn  = new TextComponent("  §8[§cUnlock§8]");
                        unlockBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/worldreset seed " + wName + " random"));
                        unlockBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                new Text("§7Remove seed lock — use a random seed each reset")));
                        lockedLine.addExtra(unlockBtn);
                        sendComponent(sender, new BaseComponent[]{lockedLine},
                                lockedLabel + "  [Unlock: /worldreset seed " + wName + " random]");
                    } else {
                        String afterLabel = "    §eAfter reset§7: §erandom (new every reset)";
                        sendComponent(sender,
                                clickableLine(afterLabel, "Lock to current", "a",
                                        ClickEvent.Action.RUN_COMMAND,
                                        "/worldreset seed " + wName + " " + seedStr,
                                        "Lock " + wName + " to its current seed (" + seedStr + ")"),
                                afterLabel + "  [Lock to current: /worldreset seed " + wName + " " + seedStr + "]");
                    }
                } else {
                    sender.sendMessage("    " + kv("Current seed", gray("(not loaded)")));
                    if (locked != null) {
                        String lockedLabel = "    §eAfter reset§7: §bLOCKED → §f" + locked;
                        TextComponent lockedLine = new TextComponent(lockedLabel);
                        TextComponent unlockBtn  = new TextComponent("  §8[§cUnlock§8]");
                        unlockBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/worldreset seed " + wName + " random"));
                        unlockBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                new Text("§7Remove seed lock — use a random seed each reset")));
                        lockedLine.addExtra(unlockBtn);
                        sendComponent(sender, new BaseComponent[]{lockedLine},
                                lockedLabel + "  [Unlock: /worldreset seed " + wName + " random]");
                    } else {
                        sender.sendMessage("    " + kv("After reset", yellow("random (new every reset)")));
                    }
                }
            });
            sendComponent(sender,
                    clickableHint("Lock a seed:", "seed <world> <number>", "/worldreset seed "),
                    "§7  Lock: /worldreset seed <world> <number>");
            return;
        }

        if (args.length < 3) {
            msg(sender, red("Usage: /worldreset seed <world> <number|random>"));
            return;
        }

        String target  = args[1];
        String seedArg = args[2];

        if (!cfg.getWorldsToReset().contains(target)) {
            msg(sender, red("'" + target + "' is not in the worlds reset list."));
            msg(sender, yellow("Configured worlds: " + String.join(", ", cfg.getWorldsToReset())));
            msg(sender, yellow("Use /worldreset worlds add <name> to add it first."));
            return;
        }

        if (seedArg.equalsIgnoreCase("random")) {
            cfg.setSeedForWorld(target, null);
            msg(sender, green("Seed lock removed for '" + target + "'. A new random seed will be used each reset."));
        } else {
            try {
                long val = Long.parseLong(seedArg);
                cfg.setSeedForWorld(target, val);
                msg(sender, green("Seed for '" + target + "' locked to " + val + "."));
            } catch (NumberFormatException e) {
                msg(sender, red("Invalid seed '" + seedArg + "'. Provide a number or 'random'."));
            }
        }
    }

    // ── hardcore ───────────────────────────────────────────────────────────

    private void handleHardcore(CommandSender sender, String[] args) {
        ConfigManager cfg = plugin.getConfigManager();

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(header("Hardcore Mode After Reset"));
            cfg.getWorldsToReset().forEach(wName -> {
                boolean hc = cfg.isHardcoreForWorld(wName);
                String stateStr = hc ? "§cON" : "§aOFF";
                String label = "  §6▸ " + wName + "  " + stateStr;
                sendComponent(sender,
                        clickableLine(label, "Toggle", "c",
                                ClickEvent.Action.RUN_COMMAND,
                                "/worldreset hardcore " + wName,
                                "Toggle hardcore for " + wName),
                        label + "  [Toggle: /worldreset hardcore " + wName + "]");
            });
            return;
        }

        String worldName = args[1];
        boolean newVal   = !cfg.isHardcoreForWorld(worldName);
        cfg.setHardcoreForWorld(worldName, newVal);
        msg(sender, newVal
                ? red("Hardcore ON") + green(" for '" + worldName + "'. Takes effect after next reset.")
                : green("Hardcore OFF for '" + worldName + "'. Takes effect after next reset."));
    }

    // ── delete ─────────────────────────────────────────────────────────────

    private void handleDelete(CommandSender sender, String[] args) {
        ConfigManager cfg = plugin.getConfigManager();

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(header("Extra Paths Deleted on Reset"));
            List<String> paths = cfg.getExtraDeletePaths();
            if (paths.isEmpty()) sender.sendMessage(yellow("  (none)"));
            else {
                for (String p : paths) {
                    sendComponent(sender,
                            clickableLine("  §b" + p, "Remove", "c",
                                    ClickEvent.Action.RUN_COMMAND,
                                    "/worldreset delete remove " + p,
                                    "Remove " + p + " from the delete list"),
                            "  " + p + "  [Remove: /worldreset delete remove " + p + "]");
                }
            }
            sendComponent(sender,
                    clickableHint("Add a path:", "delete add", "/worldreset delete add "),
                    "§7  Add: /worldreset delete add <path>");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "add":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset delete add <path>")); break; }
                cfg.addDeletePath(args[2]);
                msg(sender, green("Added '" + args[2] + "' to the delete list."));
                break;
            case "remove":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset delete remove <path>")); break; }
                if (cfg.removeDeletePath(args[2])) msg(sender, green("Removed '" + args[2] + "'."));
                else msg(sender, yellow("'" + args[2] + "' was not in the list."));
                break;
            default:
                msg(sender, red("Usage: /worldreset delete [add|remove] <path>"));
        }
    }

    // ── preserve ───────────────────────────────────────────────────────────

    private void handlePreserve(CommandSender sender, String[] args) {
        ConfigManager cfg = plugin.getConfigManager();

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(header("Preserved Paths (Never Deleted)"));
            List<String> paths = cfg.getPreservePaths();
            if (paths.isEmpty()) sender.sendMessage(yellow("  (none — everything in the delete list will be deleted)"));
            else {
                for (String p : paths) {
                    sendComponent(sender,
                            clickableLine("  §a" + p, "Remove", "c",
                                    ClickEvent.Action.RUN_COMMAND,
                                    "/worldreset preserve remove " + p,
                                    "Remove " + p + " from the preserve list"),
                            "  " + p + "  [Remove: /worldreset preserve remove " + p + "]");
                }
            }
            sendComponent(sender,
                    clickableHint("Add a path:", "preserve add", "/worldreset preserve add "),
                    "§7  Add: /worldreset preserve add <path>");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "add":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset preserve add <path>")); break; }
                cfg.addPreservePath(args[2]);
                msg(sender, green("Added '" + args[2] + "' to the preserve list."));
                break;
            case "remove":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset preserve remove <path>")); break; }
                if (cfg.removePreservePath(args[2])) msg(sender, green("Removed '" + args[2] + "'."));
                else msg(sender, yellow("'" + args[2] + "' was not in the preserve list."));
                break;
            default:
                msg(sender, red("Usage: /worldreset preserve [add|remove] <path>"));
        }
    }

    // ── gamerule (FIX-D: hardcore intercepted as a synthetic gamerule) ─────

    private void handleGamerule(CommandSender sender, String[] args) {
        ConfigManager cfg = plugin.getConfigManager();

        // No extra args → show list
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(header("Gamerules Applied After Reset"));

            // ── Synthetic "hardcore" row ───────────────────────────────────
            // hardcore is a world flag (not a real gamerule), but we expose it here
            // because admins expect to find it alongside gamerules.
            List<String> worldsList = cfg.getWorldsToReset();
            if (!worldsList.isEmpty()) {
                boolean firstHc = cfg.isHardcoreForWorld(worldsList.get(0));
                boolean allSame = true;
                for (String w : worldsList) {
                    if (cfg.isHardcoreForWorld(w) != firstHc) { allSame = false; break; }
                }
                String hcDisplay = allSame
                        ? (firstHc ? red("true") : green("false"))
                        : yellow("mixed");
                String hcLabel = "  §bhardcore §7= " + hcDisplay
                        + gray("  (world flag — applies to all worlds)");
                TextComponent hcLine = new TextComponent(hcLabel);
                TextComponent hcEditBtn = new TextComponent(" §8[§eEdit§8]");
                hcEditBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                        "/worldreset gamerule hardcore "));
                hcEditBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text("§7Set hardcore for all worlds\n§7true = players banned on death")));
                hcLine.addExtra(hcEditBtn);
                sendComponent(sender, new BaseComponent[]{hcLine},
                        hcLabel + "  [Edit: /worldreset gamerule hardcore true|false]");
            }

            Map<String, String> rules = cfg.getGamerules();
            if (rules.isEmpty()) {
                sender.sendMessage(yellow("  (no gamerules configured — vanilla defaults will apply)"));
            } else {
                for (Map.Entry<String, String> e : rules.entrySet()) {
                    String k = e.getKey(), v = e.getValue();
                    String label = "  §b" + k + " §7= §f" + v;
                    TextComponent line = new TextComponent(label);
                    TextComponent editBtn = new TextComponent(" §8[§eEdit§8]");
                    editBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/worldreset gamerule " + k + " "));
                    editBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new Text("§7Edit value for §b" + k)));
                    TextComponent removeBtn = new TextComponent(" §8[§cRemove§8]");
                    removeBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/worldreset gamerule remove " + k));
                    removeBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new Text("§7Remove §b" + k + " §7from the list")));
                    line.addExtra(editBtn);
                    line.addExtra(removeBtn);
                    sendComponent(sender, new BaseComponent[]{line},
                            label + "  [Edit: /worldreset gamerule " + k + " ]  [Remove: /worldreset gamerule remove " + k + "]");
                }
            }
            sendComponent(sender,
                    clickableHint("Set a gamerule:", "gamerule <rule> <value>", "/worldreset gamerule "),
                    "§7  Set: /worldreset gamerule <rule> <value>");
            return;
        }

        // ── Intercept "hardcore" before normal GameRule lookup ─────────────
        if (args[1].equalsIgnoreCase("hardcore")) {
            if (args.length < 3) {
                // Show current per-world status and usage hint
                msg(sender, red("Usage: /worldreset gamerule hardcore <true|false>"));
                msg(sender, gray("  Sets hardcore on ALL worlds in the reset list."));
                for (String wn : cfg.getWorldsToReset()) {
                    boolean cur = cfg.isHardcoreForWorld(wn);
                    msg(sender, "  " + gold("▸ " + wn) + " §7= " + (cur ? red("true") : green("false")));
                }
                return;
            }
            String valStr = args[2].toLowerCase();
            if (!valStr.equals("true") && !valStr.equals("false")) {
                msg(sender, red("hardcore must be 'true' or 'false'."));
                return;
            }
            boolean hcVal = Boolean.parseBoolean(valStr);
            List<String> worldsForHc = cfg.getWorldsToReset();
            if (worldsForHc.isEmpty()) {
                msg(sender, yellow("No worlds configured — nothing to set hardcore on."));
                return;
            }
            for (String wn : worldsForHc) {
                cfg.setHardcoreForWorld(wn, hcVal);
            }
            msg(sender, green("hardcore = " + valStr + " applied to "
                    + worldsForHc.size() + " world(s). Takes effect after next reset."));
            if (hcVal) {
                msg(sender, yellow("  ⚠ Hardcore mode: players are permanently banned on death."));
            }
            return;
        }

        if (args[1].equalsIgnoreCase("remove")) {
            if (args.length < 3) { msg(sender, red("Usage: /worldreset gamerule remove <rule>")); return; }
            // Intercept "hardcore" remove — reset to false for all worlds
            if (args[2].equalsIgnoreCase("hardcore")) {
                for (String wn : cfg.getWorldsToReset()) {
                    cfg.setHardcoreForWorld(wn, false);
                }
                msg(sender, green("hardcore reset to false for all worlds."));
                return;
            }
            if (cfg.removeGamerule(args[2])) msg(sender, green("Removed gamerule: " + args[2]));
            else msg(sender, yellow("Gamerule '" + args[2] + "' was not in the list."));
            return;
        }

        if (args.length < 3) {
            msg(sender, red("Usage: /worldreset gamerule <rule> <value>"));
            return;
        }
        cfg.setGamerule(args[1], args[2]);
        msg(sender, green("Gamerule set: " + args[1] + " = " + args[2]));
    }

    // ── schedule ───────────────────────────────────────────────────────────

    private void handleSchedule(CommandSender sender, String[] args) {
        ConfigManager   cfg = plugin.getConfigManager();
        ScheduleManager sm  = plugin.getScheduleManager();

        if (args.length < 2) {
            sender.sendMessage(header("Auto-Reset Schedule"));
            sender.sendMessage(kv("Enabled",    bool(cfg.isScheduleEnabled())));
            sender.sendMessage(kv("Running",    bool(sm.isRunning())));
            sender.sendMessage(kv("Mode",       cfg.getScheduleMode()));
            sender.sendMessage(kv("Interval",   cfg.getScheduleIntervalSecs() + "s"));
            sender.sendMessage(kv("Min interval", cfg.getScheduleMinIntervalSecs() + "s (floor)"));
            sender.sendMessage(kv("Daily time", cfg.getScheduleDailyTime()));
            sender.sendMessage(kv("Timezone",   cfg.getScheduleTimezone()));
            String nextFire = sm.getNextFireDescription();
            if (!nextFire.isEmpty()) {
                sender.sendMessage(kv("Next reset", nextFire));
            }
            sender.sendMessage(gray("  enable/disable  — toggle schedule"));
            sender.sendMessage(gray("  mode interval   — reset every N seconds"));
            sender.sendMessage(gray("  mode daily      — reset once a day at HH:MM"));
            sender.sendMessage(gray("  Example: /worldreset schedule interval 3600"));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "enable":
                cfg.setScheduleEnabled(true);
                sm.restart();
                msg(sender, green("Schedule enabled."));
                break;
            case "disable":
                cfg.setScheduleEnabled(false);
                sm.stop();
                msg(sender, green("Schedule disabled."));
                break;
            case "mode":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset schedule mode <interval|daily>")); break; }
                String mode = args[2].toLowerCase();
                if (!mode.equals("interval") && !mode.equals("daily")) {
                    msg(sender, red("Mode must be 'interval' or 'daily'.")); break;
                }
                cfg.setScheduleMode(mode);
                sm.restart();
                msg(sender, green("Schedule mode set to '" + mode + "'."));
                break;
            case "interval":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset schedule interval <seconds>")); break; }
                try {
                    int secs    = Integer.parseInt(args[2]);
                    int minSecs = cfg.getScheduleMinIntervalSecs();
                    cfg.setScheduleIntervalSecs(secs);
                    sm.restart();
                    msg(sender, green("Schedule interval set to " + Math.max(minSecs, secs) + "s."));
                } catch (NumberFormatException e) {
                    msg(sender, red("Must be a number (seconds)."));
                }
                break;
            case "daily":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset schedule daily <HH:MM>")); break; }
                if (!args[2].matches("\\d{1,2}:\\d{2}")) {
                    msg(sender, red("Time must be in HH:MM format (e.g. 04:00).")); break;
                }
                cfg.setScheduleDailyTime(args[2]);
                sm.restart();
                msg(sender, green("Daily reset time set to " + args[2] + "."));
                break;
            default:
                msg(sender, red("Unknown option. See /worldreset help for schedule commands."));
        }
    }

    // ── backup ─────────────────────────────────────────────────────────────

    private void handleBackup(CommandSender sender, String[] args) {
        ConfigManager cfg = plugin.getConfigManager();

        if (args.length < 2) {
            sender.sendMessage(header("Backup Settings"));
            sender.sendMessage(kv("Enabled",    bool(cfg.isBackupEnabled())));
            sender.sendMessage(kv("Directory",  cfg.getBackupDirectory()));
            sender.sendMessage(kv("Keep",       String.valueOf(cfg.getBackupKeep()) + " backups per world"));
            sender.sendMessage(gray("  now           — immediately zip all reset worlds"));
            sender.sendMessage(gray("  enable/disable"));
            sender.sendMessage(gray("  keep <n>      — number of backups to keep per world"));
            sender.sendMessage(gray("  dir <path>    — backup directory (relative to server root)"));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "now":
                sender.sendMessage(green("Starting async backup of "
                        + cfg.getWorldsToReset().size() + " world(s)…"));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    plugin.getResetManager().runManualBackup();
                    Bukkit.getScheduler().runTask(plugin, () ->
                            msg(sender, green("Backup complete. Check '" + cfg.getBackupDirectory() + "'.")));
                });
                break;
            case "enable":
                cfg.setBackupEnabled(true);
                msg(sender, green("Backup enabled."));
                break;
            case "disable":
                cfg.setBackupEnabled(false);
                msg(sender, green("Backup disabled."));
                break;
            case "keep":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset backup keep <n>")); break; }
                try {
                    int n = Integer.parseInt(args[2]);
                    cfg.setBackupKeep(n);
                    msg(sender, green("Keeping " + Math.max(1, n) + " backup(s) per world."));
                } catch (NumberFormatException e) {
                    msg(sender, red("Must be a number."));
                }
                break;
            case "dir":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset backup dir <path>")); break; }
                cfg.setBackupDirectory(args[2]);
                msg(sender, green("Backup directory set to '" + args[2] + "'."));
                break;
            default:
                msg(sender, red("Unknown option. Run /worldreset backup to see options."));
        }
    }

    // ── generator ──────────────────────────────────────────────────────────

    private void handleGenerator(CommandSender sender, String[] args) {
        ConfigManager cfg = plugin.getConfigManager();

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(header("Custom World Generators"));
            Map<String, String> gens = cfg.getAllWorldGenerators();
            if (gens.isEmpty()) {
                sender.sendMessage(yellow("  (none — all worlds use the vanilla generator)"));
            } else {
                gens.forEach((w, g) -> sender.sendMessage("  " + gold("▸ " + w) + "  " + aqua(g)));
            }
            sender.sendMessage(gray("  Set:    /worldreset generator <world> <PluginName[:id]>"));
            sender.sendMessage(gray("  Remove: /worldreset generator <world> vanilla"));
            return;
        }

        if (args.length < 3) {
            msg(sender, red("Usage: /worldreset generator <world> <PluginName[:id]|vanilla>"));
            return;
        }

        String worldName = args[1];
        String genArg    = args[2];

        if (genArg.equalsIgnoreCase("vanilla")) {
            cfg.setWorldGenerator(worldName, null);
            msg(sender, green("Generator for '" + worldName + "' reset to vanilla."));
        } else {
            cfg.setWorldGenerator(worldName, genArg);
            msg(sender, green("Generator for '" + worldName + "' set to '" + genArg + "'."));
            msg(sender, gray("  The generator plugin must be loaded when the reset runs."));
        }
    }

    // ── environment ────────────────────────────────────────────────────────

    private void handleEnvironment(CommandSender sender, String[] args) {
        ConfigManager cfg = plugin.getConfigManager();

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(header("World Environment Overrides"));
            Map<String, String> envs = cfg.getAllWorldEnvironments();
            if (envs.isEmpty()) {
                sender.sendMessage(yellow("  (none — environments inferred from world name)"));
                sender.sendMessage(gray("    Suffix _nether → NETHER | _the_end → THE_END | other → NORMAL"));
            } else {
                envs.forEach((w, e) -> sender.sendMessage("  " + gold("▸ " + w) + "  " + aqua(e)));
            }
            sender.sendMessage(gray("  Set:    /worldreset environment <world> <NORMAL|NETHER|THE_END>"));
            sender.sendMessage(gray("  Remove: /worldreset environment <world> auto"));
            return;
        }

        if (args.length < 3) {
            msg(sender, red("Usage: /worldreset environment <world> <NORMAL|NETHER|THE_END|auto>"));
            return;
        }

        String worldName = args[1];
        String envArg    = args[2].toUpperCase();

        if (envArg.equals("AUTO")) {
            cfg.setWorldEnvironment(worldName, null);
            msg(sender, green("Environment for '" + worldName + "' reset to auto (inferred from name)."));
            return;
        }

        try {
            World.Environment.valueOf(envArg);
        } catch (IllegalArgumentException e) {
            msg(sender, red("Invalid environment. Valid: NORMAL, NETHER, THE_END, auto"));
            return;
        }

        cfg.setWorldEnvironment(worldName, envArg);
        msg(sender, green("Environment for '" + worldName + "' set to " + envArg + "."));
    }

    // ── serverprops (original — unchanged) ────────────────────────────────

    private void handleServerProps(CommandSender sender, String[] args) {
        ConfigManager           cfg = plugin.getConfigManager();
        ServerPropertiesManager spm = plugin.getServerPropertiesManager();

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(header("Server Properties Patches (server-properties.yml)"));
            sender.sendMessage(kv("Patching enabled", bool(cfg.isServerPropertiesEnabled())));
            sender.sendMessage(kv("File",             spm.isLoaded()
                    ? spm.getLiveProperties().size() + " keys loaded from server.properties"
                    : red("server.properties could not be read!")));

            if (!cfg.isUseRestart()) {
                sender.sendMessage(yellow("  ⚠ use-restart=false — patches only apply in restart mode."));
                sender.sendMessage(yellow("    Run: /worldreset config use-restart true"));
            }
            if (!cfg.isServerPropertiesEnabled()) {
                sender.sendMessage(yellow("  ⚠ Patching is disabled. Run: /worldreset serverprops enable"));
            }

            Map<String, String> live    = spm.getLiveProperties();
            Map<String, String> patches = cfg.getServerPropertiesValues();

            if (patches.isEmpty()) {
                sender.sendMessage(yellow("  No patches configured yet."));
                sender.sendMessage(gray("  Example: /worldreset serverprops set difficulty hard"));
            } else {
                sender.sendMessage(gray("  Patch list (will be written to server.properties on reset):"));
                for (Map.Entry<String, String> e : patches.entrySet()) {
                    String key     = e.getKey();
                    String newVal  = e.getValue();
                    String curVal  = live.get(key);
                    String label;
                    if (curVal == null) {
                        label = "  §6+ " + key + " §7= §a" + newVal + " §7(new key)";
                    } else if (!curVal.equals(newVal)) {
                        label = "  §b" + key + " §7now=§f" + curVal + " §6→ will set=§a" + newVal;
                    } else {
                        label = "  §b" + key + " §7= §f" + curVal + " §7(already set)";
                    }
                    TextComponent line = new TextComponent(label);
                    TextComponent editBtn = new TextComponent(" §8[§eEdit§8]");
                    editBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/worldreset serverprops set " + key + " "));
                    editBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new Text("§7Edit patch value for §b" + key)));
                    TextComponent removeBtn = new TextComponent(" §8[§cRemove§8]");
                    removeBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/worldreset serverprops remove " + key));
                    removeBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new Text("§7Remove §b" + key + " §7from the patch list")));
                    line.addExtra(editBtn);
                    line.addExtra(removeBtn);
                    sendComponent(sender, new BaseComponent[]{line},
                            label + "  [Edit]  [Remove: /worldreset serverprops remove " + key + "]");
                }
            }

            sendComponent(sender,
                    clickableHint("Add/update a property:", "serverprops set <key> <value>", "/worldreset serverprops set "),
                    "§7  /worldreset serverprops set <key> <value>");
            sender.sendMessage(gray("  Tip: use §e/worldreset props§7 for a grouped view of all 54 standard keys."));
            return;
        }

        switch (args[1].toLowerCase()) {

            case "reload":
                spm.reload();
                cfg.reloadServerPropsConfig();
                msg(sender, green("server-properties.yml and server.properties cache reloaded ("
                        + spm.getLiveProperties().size() + " keys)."));
                break;

            case "list-all": {
                Map<String, String> patches = cfg.getServerPropertiesValues();
                Map<String, String> live    = spm.getLiveProperties();
                sender.sendMessage(header("All Standard server.properties Keys"));
                sender.sendMessage(gray("  §7Live = current value in server.properties"));
                sender.sendMessage(gray("  §aPatch§7 = value that will be written on reset"));
                sender.sendMessage(gray("  Click §e[Set]§7 to add a key to the patch list."));
                sender.sendMessage("");
                for (String k : ALL_SERVER_PROP_KEYS) {
                    String liveVal    = live.get(k);
                    String patchedVal = patches.get(k);
                    StringBuilder line = new StringBuilder();
                    line.append("  §b").append(k).append("§7: ");
                    if (liveVal != null) {
                        line.append("§f").append(liveVal);
                    } else {
                        line.append("§8(not in file)");
                    }
                    if (patchedVal != null) {
                        if (patchedVal.equals(liveVal)) {
                            line.append("  §a[patched: ").append(patchedVal).append("]");
                        } else {
                            line.append("  §6→patch: §a").append(patchedVal);
                        }
                    }
                    String desc = serverPropDescription(k);
                    if (sender instanceof Player) {
                        TextComponent tLine = new TextComponent(line.toString());
                        TextComponent setBtn = new TextComponent(" §8[§eSet§8]");
                        setBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                "/worldreset serverprops set " + k + " "));
                        setBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                new Text("§7" + (desc.isEmpty() ? k : desc)
                                        + "\n§7Click to set patch value for §b" + k)));
                        tLine.addExtra(setBtn);
                        ((Player) sender).spigot().sendMessage(tLine);
                    } else {
                        sender.sendMessage(line + "  [/worldreset serverprops set " + k + " ...]"
                                + (desc.isEmpty() ? "" : "  — " + desc));
                    }
                }
                sender.sendMessage(gray("  " + ALL_SERVER_PROP_KEYS.size() + " standard keys listed."));
                break;
            }

            case "enable":
                cfg.setServerPropertiesEnabled(true);
                msg(sender, green("Server properties patching enabled."));
                if (!cfg.isUseRestart()) {
                    msg(sender, yellow("  ⚠ Patches only apply when use-restart=true."));
                    msg(sender, yellow("    Run: /worldreset config use-restart true"));
                }
                break;

            case "disable":
                cfg.setServerPropertiesEnabled(false);
                msg(sender, green("Server properties patching disabled."));
                break;

            case "set":
                if (args.length < 4) {
                    msg(sender, red("Usage: /worldreset serverprops set <key> <value>"));
                    msg(sender, gray("  Example: /worldreset serverprops set difficulty hard"));
                    break;
                }
                {
                    String key   = args[2];
                    String value = joinFrom(args, 3);
                    cfg.setServerPropertiesValue(key, value);
                    String curLive = spm.getLiveValue(key);
                    if (curLive == null) {
                        msg(sender, green("Patch added: ") + aqua(key) + green(" = ") + white(value)
                                + gray("  (new key — will be added to server.properties)"));
                    } else if (curLive.equals(value)) {
                        msg(sender, green("Patch set: ") + aqua(key) + green(" = ") + white(value)
                                + gray("  (same as current value)"));
                    } else {
                        msg(sender, green("Patch set: ") + aqua(key)
                                + gray("  now=") + white(curLive)
                                + gold("  →  will set=") + green(value));
                    }
                    if (!cfg.isUseRestart()) {
                        msg(sender, yellow("  ⚠ use-restart=false — run /worldreset config use-restart true"));
                    }
                }
                break;

            case "remove":
                if (args.length < 3) { msg(sender, red("Usage: /worldreset serverprops remove <key>")); break; }
                if (cfg.removeServerPropertiesValue(args[2])) {
                    msg(sender, green("Removed '" + args[2] + "' from the patch list."));
                } else {
                    msg(sender, yellow("'" + args[2] + "' was not in the patch list."));
                }
                break;

            default: {
                String key   = args[1];
                String value = joinFrom(args, 2);
                if (value.isEmpty()) {
                    String patched = cfg.getServerPropertiesValues().get(key);
                    String live2   = spm.getLiveValue(key);
                    msg(sender, kv("Current in server.properties", live2 != null ? live2 : gray("(key not found)")));
                    if (patched != null) msg(sender, kv("Will be set to on reset", patched));
                    msg(sender, gray("  Usage: /worldreset serverprops " + key + " <value>"));
                    break;
                }
                cfg.setServerPropertiesValue(key, value);
                String curLive = spm.getLiveValue(key);
                if (curLive == null) {
                    msg(sender, green("Patch added: ") + aqua(key) + green(" = ") + white(value)
                            + gray("  (new key — will be added to server.properties)"));
                } else if (curLive.equals(value)) {
                    msg(sender, green("Patch set: ") + aqua(key) + green(" = ") + white(value)
                            + gray("  (same as current value)"));
                } else {
                    msg(sender, green("Patch set: ") + aqua(key)
                            + gray("  now=") + white(curLive)
                            + gold("  →  will set=") + green(value));
                }
                if (!cfg.isUseRestart()) {
                    msg(sender, yellow("  ⚠ use-restart=false — run /worldreset config use-restart true"));
                }
            }
            break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW: /worldreset props — direct editor for ALL 54 server.properties keys
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Direct server.properties editor covering all 54 standard Minecraft keys.
     *
     * Completely separate from /worldreset serverprops in UX (but uses the same
     * ConfigManager / ServerPropertiesManager backend for storage).
     *
     *   /worldreset props                → grouped list of ALL 54 keys
     *   /worldreset props <key>          → detail view for one key
     *   /worldreset props <key> <value>  → set patch for any key
     *   /worldreset props remove <key>   → remove a patch
     *   /worldreset props enable         → toggle patching on
     *   /worldreset props disable        → toggle patching off
     *   /worldreset props reload         → refresh server.properties cache
     */
    private void handleProps(CommandSender sender, String[] args) {
        ConfigManager           cfg = plugin.getConfigManager();
        ServerPropertiesManager spm = plugin.getServerPropertiesManager();

        // No args → grouped view of all 54 keys
        if (args.length < 2) {
            showAllPropsGrouped(sender, cfg, spm);
            return;
        }

        String sub = args[1].toLowerCase();

        switch (sub) {
            case "enable":
                cfg.setServerPropertiesEnabled(true);
                msg(sender, green("Server properties patching enabled."));
                if (!cfg.isUseRestart())
                    msg(sender, yellow("  ⚠ Patches only apply when use-restart=true. "
                            + "Run: /worldreset config use-restart true"));
                break;

            case "disable":
                cfg.setServerPropertiesEnabled(false);
                msg(sender, green("Server properties patching disabled."));
                break;

            case "reload":
                spm.reload();
                cfg.reloadServerPropsConfig();
                msg(sender, green("server.properties cache reloaded ("
                        + spm.getLiveProperties().size() + " keys)."));
                break;

            case "remove":
            case "reset":
            case "clear":
                if (args.length < 3) {
                    msg(sender, red("Usage: /worldreset props remove <key>"));
                    break;
                }
                if (cfg.removeServerPropertiesValue(args[2]))
                    msg(sender, green("Patch removed for '" + args[2] + "'."));
                else
                    msg(sender, yellow("No patch was set for '" + args[2] + "'."));
                break;

            default: {
                // /worldreset props <key> [value]
                String key = args[1];
                if (args.length < 3) {
                    showSingleProp(sender, cfg, spm, key);
                } else {
                    String value = joinFrom(args, 2);
                    applyPropPatch(sender, cfg, spm, key, value);
                }
            }
        }
    }

    /**
     * Print all 54 server properties grouped by category.
     * Each row shows: key, live value, patch value (if any), [Set] and [X] buttons.
     */
    private void showAllPropsGrouped(CommandSender sender, ConfigManager cfg,
                                     ServerPropertiesManager spm) {
        Map<String, String> live    = spm.getLiveProperties();
        Map<String, String> patches = cfg.getServerPropertiesValues();
        int patchCount = patches.size();

        sender.sendMessage(header("Server Properties — All 54 Keys"));
        sender.sendMessage("  " + kv("Patching", bool(cfg.isServerPropertiesEnabled()))
                + gray("   use-restart: " + bool(cfg.isUseRestart())));

        if (!cfg.isUseRestart()) {
            sender.sendMessage(yellow("  ⚠ Patches only apply in restart mode.  /worldreset config use-restart true"));
        }
        if (!cfg.isServerPropertiesEnabled()) {
            sender.sendMessage(yellow("  ⚠ Patching is off.  /worldreset props enable"));
        }
        if (patchCount > 0) {
            sender.sendMessage(gray("  " + patchCount + " patch(es) active.  "
                    + "§e[/worldreset props <key> <value>]§7 to add more."));
        } else {
            sender.sendMessage(gray("  No patches yet.  §e/worldreset props <key> <value>§7 to set any key."));
        }

        for (Map.Entry<String, List<String>> cat : PROPS_CATEGORIES.entrySet()) {
            sender.sendMessage(section(cat.getKey()));
            for (String k : cat.getValue()) {
                printPropRow(sender, k, live.get(k), patches.get(k));
            }
        }
        sender.sendMessage(gray("  54 standard Minecraft Java Edition server.properties keys."));
        sender.sendMessage(gray("  §e/worldreset props <key>§7 for detail.  "
                + "§e/worldreset props reload§7 to refresh cache."));
    }

    /**
     * Print one row for a server property:
     *   key: liveValue  [patch indicator]  [Set] [X]
     */
    private void printPropRow(CommandSender sender, String key,
                              String liveVal, String patchedVal) {
        StringBuilder sb = new StringBuilder("  §b").append(key).append("§7: ");
        if (liveVal != null) sb.append("§f").append(liveVal);
        else sb.append("§8(absent)");

        if (patchedVal != null) {
            if (patchedVal.equals(liveVal)) sb.append("  §a[✓ patch=same]");
            else sb.append("  §6→ §apatch: ").append(patchedVal);
        }

        String lineText = sb.toString();
        String desc = serverPropDescription(key);

        if (sender instanceof Player) {
            TextComponent line = new TextComponent(lineText);
            TextComponent setBtn = new TextComponent(" §8[§eSet§8]");
            setBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                    "/worldreset props " + key + " "));
            setBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text((desc.isEmpty() ? "§b" + key : "§7" + desc)
                            + "\n§7Click to pre-fill §e/worldreset props " + key + " ")));
            line.addExtra(setBtn);
            if (patchedVal != null) {
                TextComponent xBtn = new TextComponent(" §8[§cX§8]");
                xBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/worldreset props remove " + key));
                xBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text("§7Remove patch for §b" + key)));
                line.addExtra(xBtn);
            }
            ((Player) sender).spigot().sendMessage(line);
        } else {
            sender.sendMessage(lineText
                    + "  [/worldreset props " + key + " <value>]"
                    + (desc.isEmpty() ? "" : "  — " + desc));
        }
    }

    /**
     * Show a detailed info panel for a single server.properties key:
     * description, live value, patch value, valid options, and action buttons.
     */
    private void showSingleProp(CommandSender sender, ConfigManager cfg,
                                ServerPropertiesManager spm, String key) {
        String live    = spm.getLiveValue(key);
        String patched = cfg.getServerPropertiesValues().get(key);
        String desc    = serverPropDescription(key);

        sender.sendMessage(header("Property: " + key));
        if (!desc.isEmpty())
            sender.sendMessage("  " + gray(desc));
        sender.sendMessage(kv("Current (server.properties)", live != null ? white(live) : gray("(key not found — will be added)")));
        if (patched != null) {
            if (patched.equals(live))
                sender.sendMessage(kv("Patch", green(patched) + gray("  (same as current — no change)")));
            else
                sender.sendMessage(kv("Will be changed to", green(patched)
                        + gray("  (was: " + (live != null ? live : "absent") + ")")));
        } else {
            sender.sendMessage(kv("Patch", yellow("(none — will not be changed on reset)")));
        }

        List<String> hints = getServerPropValueCompletions(key);
        if (!hints.isEmpty())
            sender.sendMessage(gray("  Valid values: §f" + String.join(", ", hints)));

        sender.sendMessage("");
        sendComponent(sender,
                clickableHint("Set value:", "props " + key + " <value>",
                        "/worldreset props " + key + " "),
                "§7  Set: /worldreset props " + key + " <value>");
        if (patched != null) {
            sendComponent(sender,
                    clickableLine(gray("  Remove current patch"), "Remove patch", "c",
                            ClickEvent.Action.RUN_COMMAND,
                            "/worldreset props remove " + key,
                            "Remove patch for " + key),
                    "§7  Remove: /worldreset props remove " + key);
        }
        if (!cfg.isUseRestart()) {
            sender.sendMessage(yellow("  ⚠ Patches only apply in restart mode."));
        }
    }

    /**
     * Write a server.properties patch and print concise feedback showing
     * old value → new value.
     */
    private void applyPropPatch(CommandSender sender, ConfigManager cfg,
                                ServerPropertiesManager spm, String key, String value) {
        cfg.setServerPropertiesValue(key, value);
        String live = spm.getLiveValue(key);
        if (live == null) {
            msg(sender, green("Patch added: ") + aqua(key) + green(" = ") + white(value)
                    + gray("  (new key — will be inserted into server.properties)"));
        } else if (live.equals(value)) {
            msg(sender, green("Patch set: ") + aqua(key) + green(" = ") + white(value)
                    + gray("  (already at this value)"));
        } else {
            msg(sender, green("Patch set: ") + aqua(key)
                    + gray("  now=") + white(live) + gold("  →  ") + green(value));
        }
        if (!cfg.isUseRestart()) {
            msg(sender, yellow("  ⚠ use-restart=false — patch won't apply until restart mode is on.  "
                    + "/worldreset config use-restart true"));
        }
    }

    // ── Help (paginated, 5 pages) ──────────────────────────────────────────

    private static final int HELP_TOTAL_PAGES = 5;

    private void sendHelp(CommandSender sender, int page) {
        page = Math.max(1, Math.min(page, HELP_TOTAL_PAGES));
        sender.sendMessage(header("WorldReset v1.2.0 — Help  (page " + page + "/" + HELP_TOTAL_PAGES + ")"));

        switch (page) {
            case 1:
                sender.sendMessage(section("Core"));
                help(sender, "/worldreset start",                 "Begin a reset (asks for confirmation). Add --now to skip countdown.");
                help(sender, "/worldreset start --now --confirm", "Start immediately, no countdown, no second prompt.");
                help(sender, "/worldreset cancel",                "Stop the running countdown.");
                help(sender, "/worldreset status",                "Full snapshot: worlds, seeds, schedule, etc.");
                help(sender, "/worldreset reload",                "Reload config.yml and server-properties.yml from disk.");

                sender.sendMessage(section("Quick Settings  (/worldreset config <key> <value>)"));
                help(sender, "/worldreset config countdown <seconds>",              "How long the countdown runs before reset (0 = instant).");
                help(sender, "/worldreset config interval <seconds>",               "How often the countdown is broadcast.");
                help(sender, "/worldreset config kick-msg <text>",                  "Message players see when kicked during a reset.");
                help(sender, "/worldreset config difficulty <PEACEFUL|EASY|NORMAL|HARD>", "World difficulty after reset.");
                help(sender, "/worldreset config use-restart <true|false>",         "true = restart the server; false = regenerate worlds live.");
                help(sender, "/worldreset config delete-async <true|false>",        "true = delete files off the main thread (recommended).");
                help(sender, "/worldreset config whitelist-during-reset <true|false>", "Lock new joiners out while the reset runs.");
                break;

            case 2:
                sender.sendMessage(section("Worlds  (/worldreset worlds)"));
                help(sender, "/worldreset worlds",               "Show worlds that will be deleted and regenerated.");
                help(sender, "/worldreset worlds add <name>",    "Add a world to the reset list.");
                help(sender, "/worldreset worlds remove <name>", "Remove a world from the reset list.");

                sender.sendMessage(section("Seeds  (/worldreset seed)"));
                help(sender, "/worldreset seed",                       "Show the seed setting for every world.");
                help(sender, "/worldreset seed <world> <number>",      "Lock a world to a fixed seed after reset.");
                help(sender, "/worldreset seed <world> random",        "Unlock — use a fresh random seed each reset.");

                sender.sendMessage(section("Hardcore / Environment / Generator"));
                help(sender, "/worldreset hardcore",                              "Show / toggle permadeath per world.");
                help(sender, "/worldreset hardcore <world>",                      "Toggle hardcore for that world.");
                help(sender, "/worldreset environment",                           "Show environment overrides.");
                help(sender, "/worldreset environment <world> <NORMAL|NETHER|THE_END|auto>", "Set environment.");
                help(sender, "/worldreset generator",                             "Show custom generators.");
                help(sender, "/worldreset generator <world> <PluginName|vanilla>", "Set or clear a generator.");
                break;

            case 3:
                sender.sendMessage(section("Gamerules  (/worldreset gamerule)"));
                help(sender, "/worldreset gamerule",                 "Show all gamerules + hardcore flag applied after reset.");
                help(sender, "/worldreset gamerule hardcore <true|false>", "Set hardcore for ALL worlds (special synthetic gamerule).");
                help(sender, "/worldreset gamerule <rule> <value>",  "Set a gamerule (e.g. keepInventory false).");
                help(sender, "/worldreset gamerule remove <rule>",   "Remove a gamerule from the list.");

                sender.sendMessage(section("Delete & Preserve  (/worldreset delete | preserve)"));
                help(sender, "/worldreset delete",                 "Show extra folders/files deleted on reset.");
                help(sender, "/worldreset delete add <path>",      "Add a path to delete (e.g. logs).");
                help(sender, "/worldreset delete remove <path>",   "Remove a path from the delete list.");
                help(sender, "/worldreset preserve",               "Show protected paths (never deleted).");
                help(sender, "/worldreset preserve add <path>",    "Protect a path from deletion.");
                help(sender, "/worldreset preserve remove <path>", "Remove protection from a path.");

                sender.sendMessage(section("Schedule  (/worldreset schedule)"));
                help(sender, "/worldreset schedule",                       "Show schedule status.");
                help(sender, "/worldreset schedule enable",                "Turn on the auto-reset schedule.");
                help(sender, "/worldreset schedule disable",               "Turn off the auto-reset schedule.");
                help(sender, "/worldreset schedule mode <interval|daily>", "Switch between interval and daily mode.");
                help(sender, "/worldreset schedule interval <seconds>",    "Reset every N seconds (interval mode).");
                help(sender, "/worldreset schedule daily <HH:MM>",         "Reset once per day at this time (daily mode).");
                break;

            case 4:
                sender.sendMessage(section("Backup  (/worldreset backup)"));
                help(sender, "/worldreset backup",             "Show backup settings.");
                help(sender, "/worldreset backup now",         "Immediately zip all reset worlds.");
                help(sender, "/worldreset backup enable",      "Enable automatic backup before each reset.");
                help(sender, "/worldreset backup disable",     "Disable automatic backup.");
                help(sender, "/worldreset backup keep <n>",    "Keep the N most recent backups per world.");
                help(sender, "/worldreset backup dir <path>",  "Set the backup folder (relative to server root).");

                sender.sendMessage(section("Server Properties — legacy  (/worldreset serverprops)"));
                help(sender, "/worldreset serverprops",                   "Show patch values and current server.properties state.");
                help(sender, "/worldreset serverprops list-all",          "List ALL keys with live values, patches, and descriptions.");
                help(sender, "/worldreset serverprops set <key> <value>", "Add or update a patch value.");
                help(sender, "/worldreset serverprops remove <key>",      "Remove a key from the patch list.");
                help(sender, "/worldreset serverprops enable / disable",  "Toggle patching on or off.");
                help(sender, "/worldreset serverprops reload",            "Re-read server-properties.yml from disk.");
                break;

            case 5:
                sender.sendMessage(section("Direct Properties Editor  (/worldreset props)  — alias: p, prop"));
                help(sender, "/worldreset props",                 "Grouped view of ALL 54 standard server.properties keys.");
                help(sender, "/worldreset props <key> <value>",  "Set a patch for any key directly. Tab-complete for all 54 keys + value hints.");
                help(sender, "/worldreset props <key>",          "Detail panel: description, live value, patch, valid options.");
                help(sender, "/worldreset props remove <key>",   "Remove a patch for a specific key.");
                help(sender, "/worldreset props enable",         "Enable server.properties patching.");
                help(sender, "/worldreset props disable",        "Disable patching (patches are kept but not applied).");
                help(sender, "/worldreset props reload",         "Refresh the server.properties live-value cache from disk.");

                sender.sendMessage(section("Key categories covered by /worldreset props"));
                sender.sendMessage(gray("  Gameplay       — gamemode, difficulty, hardcore, pvp, spawn-*, etc."));
                sender.sendMessage(gray("  World Gen      — level-name, level-seed, level-type, allow-nether, etc."));
                sender.sendMessage(gray("  View/Perf      — view-distance, simulation-distance, sync-chunk-writes…"));
                sender.sendMessage(gray("  Network        — server-port, max-players, motd, online-mode, etc."));
                sender.sendMessage(gray("  RCON & Query   — enable-rcon, rcon.port, rcon.password, query.port…"));
                sender.sendMessage(gray("  Access & Ops   — white-list, enforce-whitelist, op-permission-level"));
                sender.sendMessage(gray("  Resource Pack  — resource-pack, resource-pack-sha1, etc."));
                sender.sendMessage(gray("  Modern & Misc  — enforce-secure-profile, hide-online-players, etc."));

                sender.sendMessage(gray("  Permission: worldreset.admin  (default: OP)"));
                break;
        }

        sendHelpNav(sender, page);
    }

    private void sendHelpNav(CommandSender sender, int page) {
        boolean hasPrev = page > 1;
        boolean hasNext = page < HELP_TOTAL_PAGES;

        if (sender instanceof Player) {
            TextComponent bar = new TextComponent("§8  ");
            if (hasPrev) {
                TextComponent prev = new TextComponent("§8[§b← Prev§8]");
                prev.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/worldreset help " + (page - 1)));
                prev.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text("§7Page " + (page - 1) + " of " + HELP_TOTAL_PAGES)));
                bar.addExtra(prev);
            } else {
                bar.addExtra(new TextComponent("§8[§7← Prev§8]"));
            }
            bar.addExtra(new TextComponent("  §7Page §f" + page + "§7/§f" + HELP_TOTAL_PAGES + "  "));
            if (hasNext) {
                TextComponent next = new TextComponent("§8[§bNext →§8]");
                next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/worldreset help " + (page + 1)));
                next.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text("§7Page " + (page + 1) + " of " + HELP_TOTAL_PAGES)));
                bar.addExtra(next);
            } else {
                bar.addExtra(new TextComponent("§8[§7Next →§8]"));
            }
            ((Player) sender).spigot().sendMessage(bar);
        } else {
            StringBuilder nav = new StringBuilder("  ");
            if (hasPrev) nav.append("[/worldreset help ").append(page - 1).append("]  ");
            nav.append("Page ").append(page).append("/").append(HELP_TOTAL_PAGES);
            if (hasNext) nav.append("  [/worldreset help ").append(page + 1).append("]");
            sender.sendMessage(nav.toString());
        }
    }

    // ── Tab completion ─────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String alias, String[] args) {
        if (!sender.hasPermission(PERM)) return Collections.emptyList();

        List<String> result = new ArrayList<>();
        ConfigManager cfg = plugin.getConfigManager();

        if (args.length == 1) {
            result.addAll(Arrays.asList(
                    "help", "start", "cancel", "status", "reload",
                    "config", "worlds", "seed", "hardcore",
                    "delete", "preserve", "gamerule",
                    "schedule", "backup", "history", "generator", "environment",
                    "serverprops", "props"));

        } else if (args.length == 2) {
            switch (normalizeSubCmd(args[0])) {
                case "help":
                    for (int i = 1; i <= HELP_TOTAL_PAGES; i++) result.add(String.valueOf(i));
                    break;
                case "start":
                    result.addAll(Arrays.asList("--now", "--confirm", "--now --confirm"));
                    break;
                case "config":
                    result.addAll(Arrays.asList("countdown", "interval", "kick-msg", "difficulty",
                            "use-restart", "delete-async", "whitelist-during-reset"));
                    break;
                case "worlds":
                    result.addAll(Arrays.asList("add", "remove"));
                    break;
                case "seed":
                    result.addAll(cfg.getWorldsToReset());
                    break;
                case "hardcore":
                    result.addAll(cfg.getWorldsToReset());
                    break;
                case "delete": case "preserve":
                    result.addAll(Arrays.asList("add", "remove"));
                    break;
                case "gamerule":
                    result.add("remove");
                    result.add("hardcore");       // synthetic gamerule
                    result.addAll(cfg.getGamerules().keySet());
                    break;
                case "schedule":
                    result.addAll(Arrays.asList("enable", "disable", "mode", "interval", "daily"));
                    break;
                case "backup":
                    result.addAll(Arrays.asList("now", "enable", "disable", "keep", "dir"));
                    break;
                case "generator": case "environment":
                    result.addAll(cfg.getWorldsToReset());
                    break;
                case "serverprops":
                    result.addAll(Arrays.asList("enable", "disable", "set", "remove", "reload", "list-all"));
                    result.addAll(plugin.getServerPropertiesManager().getLiveKeys());
                    for (String k : ALL_SERVER_PROP_KEYS) { if (!result.contains(k)) result.add(k); }
                    break;
                case "props":
                    // Sub-commands + all 54 standard keys (for /worldreset props <key> [value])
                    result.addAll(Arrays.asList("enable", "disable", "reload", "remove", "reset", "clear"));
                    result.addAll(plugin.getServerPropertiesManager().getLiveKeys());
                    for (String k : ALL_SERVER_PROP_KEYS) { if (!result.contains(k)) result.add(k); }
                    break;
            }

        } else if (args.length == 3) {
            switch (normalizeSubCmd(args[0])) {
                case "config":
                    switch (args[1].toLowerCase()) {
                        case "difficulty":
                            result.addAll(Arrays.asList("PEACEFUL","EASY","NORMAL","HARD")); break;
                        case "use-restart": case "delete-async": case "whitelist-during-reset":
                            result.addAll(Arrays.asList("true","false")); break;
                        case "countdown":
                            result.addAll(Arrays.asList("0","5","10","30","60")); break;
                        case "interval":
                            result.addAll(Arrays.asList("5","10","30")); break;
                    }
                    break;
                case "gamerule":
                    if (args[1].equalsIgnoreCase("remove")) {
                        result.add("hardcore");
                        result.addAll(cfg.getGamerules().keySet());
                    } else if (args[1].equalsIgnoreCase("hardcore")) {
                        result.addAll(Arrays.asList("true","false"));
                    } else {
                        result.addAll(Arrays.asList("true","false"));
                    }
                    break;
                case "seed":
                    {
                        World w = Bukkit.getWorld(args[1]);
                        if (w != null) result.add(Long.toString(w.getSeed()));
                        result.add("random");
                    }
                    break;
                case "worlds":
                    if (args[1].equalsIgnoreCase("remove")) result.addAll(cfg.getWorldsToReset());
                    break;
                case "delete":
                    if (args[1].equalsIgnoreCase("remove")) result.addAll(cfg.getExtraDeletePaths());
                    break;
                case "preserve":
                    if (args[1].equalsIgnoreCase("remove")) result.addAll(cfg.getPreservePaths());
                    break;
                case "schedule":
                    if (args[1].equalsIgnoreCase("mode"))
                        result.addAll(Arrays.asList("interval","daily"));
                    break;
                case "environment":
                    result.addAll(Arrays.asList("NORMAL","NETHER","THE_END","auto"));
                    break;
                case "generator":
                    result.add("vanilla");
                    break;
                case "serverprops":
                    if (args[1].equalsIgnoreCase("set")) {
                        result.addAll(plugin.getServerPropertiesManager().getLiveKeys());
                        for (String k : ALL_SERVER_PROP_KEYS) { if (!result.contains(k)) result.add(k); }
                    } else if (args[1].equalsIgnoreCase("remove")) {
                        result.addAll(cfg.getServerPropertiesValues().keySet());
                    } else {
                        result.addAll(getServerPropValueCompletions(args[1]));
                    }
                    break;
                case "props":
                    if (args[1].equalsIgnoreCase("remove")
                            || args[1].equalsIgnoreCase("reset")
                            || args[1].equalsIgnoreCase("clear")) {
                        // Offer keys that actually have a patch set
                        result.addAll(cfg.getServerPropertiesValues().keySet());
                    } else {
                        // /worldreset props <key> <value> — offer value completions
                        result.addAll(getServerPropValueCompletions(args[1]));
                    }
                    break;
            }

        } else if (args.length == 4) {
            String norm = normalizeSubCmd(args[0]);
            if (norm.equals("serverprops") && args[1].equalsIgnoreCase("set")) {
                result.addAll(getServerPropValueCompletions(args[2]));
            }
            // props length-4 not needed: /worldreset props <key> <value> is 3 tokens
        }

        String partial = args[args.length - 1].toLowerCase();
        result.removeIf(s -> !s.toLowerCase().startsWith(partial));
        return result;
    }

    // ── Formatting helpers ─────────────────────────────────────────────────

    private static void msg(CommandSender s, String text) { s.sendMessage(text); }

    private void sendComponent(CommandSender sender, BaseComponent[] components, String fallback) {
        if (sender instanceof Player) {
            ((Player) sender).spigot().sendMessage(components);
        } else {
            sender.sendMessage(fallback);
        }
    }

    private static BaseComponent[] clickableLine(
            String label, String btnText, String btnColour,
            ClickEvent.Action action, String command, String hoverText) {
        TextComponent line = new TextComponent(label);
        TextComponent btn  = new TextComponent(" §8[§" + btnColour + btnText + "§8]");
        btn.setClickEvent(new ClickEvent(action, command));
        btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§7" + hoverText)));
        line.addExtra(btn);
        return new BaseComponent[]{line};
    }

    private static BaseComponent[] clickableLine(
            String label, String btnText, String btnColour,
            String command, String hoverText) {
        return clickableLine(label, btnText, btnColour,
                ClickEvent.Action.SUGGEST_COMMAND, command, hoverText);
    }

    private static BaseComponent[] clickableHint(String hintText, String actionLabel, String command) {
        TextComponent hint = new TextComponent("§7  " + hintText + " ");
        TextComponent btn  = new TextComponent("§8[§a" + actionLabel + "§8]");
        btn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command));
        btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§7" + command)));
        hint.addExtra(btn);
        return new BaseComponent[]{hint};
    }

    private static String red(String s)    { return "§c" + s; }
    private static String green(String s)  { return "§a" + s; }
    private static String yellow(String s) { return "§e" + s; }
    private static String aqua(String s)   { return "§b" + s; }
    private static String gray(String s)   { return "§7" + s; }
    private static String white(String s)  { return "§f" + s; }
    private static String gold(String s)   { return "§6" + s; }

    private static String bool(boolean val) {
        return val ? green("true") : red("false");
    }

    private static String header(String title) {
        return "§8§m----------§r §6§l" + title + " §8§m----------§r";
    }

    private static String section(String title) {
        return "§7 ─── §e" + title + " §7───";
    }

    private static String kv(String key, String value) {
        return "§e" + key + "§7: §f" + value;
    }

    private void help(CommandSender sender, String cmd, String desc) {
        String base    = cmd.startsWith("/") ? cmd : "/worldreset " + cmd;
        String suggest = base.replaceAll("\\s+[<\\[(][^>\\])]*[>\\])].*$", "").trim();
        if (!suggest.equals(base.trim())) suggest = suggest + " ";

        if (sender instanceof Player) {
            TextComponent cmdLine = new TextComponent("  §b" + cmd);
            cmdLine.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggest));
            cmdLine.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text("§7Click to pre-fill in chat")));
            ((Player) sender).spigot().sendMessage(cmdLine);
            sender.sendMessage("    §7→ " + desc);
        } else {
            sender.sendMessage("  §b" + cmd);
            sender.sendMessage("    §7→ " + desc);
        }
    }

    private static String joinFrom(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length));
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equalsIgnoreCase(flag)) return true;
        return false;
    }

    private static String friendlyEnvironment(World.Environment env) {
        switch (env) {
            case NETHER:  return "Nether (NETHER)";
            case THE_END: return "The End (THE_END)";
            default:      return "Overworld (NORMAL)";
        }
    }

    private static String inferEnvironmentLabel(String name) {
        if (name.endsWith("_nether"))  return "NETHER (inferred)";
        if (name.endsWith("_the_end")) return "THE_END (inferred)";
        return "NORMAL (inferred)";
    }

    private static String normalizeSubCmd(String raw) {
        switch (raw.toLowerCase()) {
            case "s":    return "start";
            case "c":    return "cancel";
            case "st":   return "status";
            case "r":
            case "rl":   return "reload";
            case "cfg":  return "config";
            case "w":    return "worlds";
            case "gr":   return "gamerule";
            case "sp":   return "serverprops";
            case "sched":return "schedule";
            case "bk":   return "backup";
            case "hist": return "history";
            case "env":  return "environment";
            case "gen":  return "generator";
            case "p":
            case "prop": return "props";          // NEW alias
            default:     return raw.toLowerCase();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Static data: all 54 standard server.properties keys grouped by category
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All 54 standard Minecraft Java Edition server.properties keys (1.21).
     * Sorted alphabetically within each group matching Mojang's documentation.
     */
    private static final List<String> ALL_SERVER_PROP_KEYS = Arrays.asList(
        // Gameplay
        "allow-flight", "allow-nether", "difficulty", "force-gamemode",
        "function-permission-level", "gamemode", "generate-structures",
        "generator-settings", "hardcore", "level-name", "level-seed",
        "level-type", "max-build-height", "max-world-size", "pvp",
        "spawn-animals", "spawn-monsters", "spawn-npcs", "spawn-protection",
        // View / simulation
        "simulation-distance", "view-distance",
        // Network / connection
        "enable-query", "enable-rcon", "max-players", "max-tick-time",
        "motd", "network-compression-threshold", "online-mode",
        "player-idle-timeout", "prevent-proxy-connections", "query.port",
        "rate-limit", "rcon.password", "rcon.port", "server-ip", "server-port",
        "sync-chunk-writes", "use-native-transport",
        // Whitelist / ops
        "enforce-whitelist", "op-permission-level", "white-list",
        // Commands
        "enable-command-block",
        // Resource pack
        "require-resource-pack", "resource-pack", "resource-pack-prompt",
        "resource-pack-sha1",
        // 1.19+ / misc
        "bungeecord", "enable-jmx-monitoring", "enforce-secure-profile",
        "hide-online-players", "initial-disabled-packs", "initial-enabled-packs",
        "log-ips", "text-filtering-config"
    );

    /**
     * The same 54 keys organised into display categories for /worldreset props.
     * Every key in ALL_SERVER_PROP_KEYS appears exactly once across all categories.
     */
    private static final Map<String, List<String>> PROPS_CATEGORIES;
    static {
        PROPS_CATEGORIES = new LinkedHashMap<>();

        PROPS_CATEGORIES.put("Gameplay", Arrays.asList(
                "gamemode", "difficulty", "hardcore", "pvp", "force-gamemode",
                "allow-flight", "spawn-animals", "spawn-monsters", "spawn-npcs",
                "function-permission-level", "max-build-height", "max-world-size"
        ));
        PROPS_CATEGORIES.put("World Generation", Arrays.asList(
                "level-name", "level-seed", "level-type", "generator-settings",
                "allow-nether", "generate-structures", "spawn-protection"
        ));
        PROPS_CATEGORIES.put("View & Performance", Arrays.asList(
                "view-distance", "simulation-distance",
                "sync-chunk-writes", "use-native-transport"
        ));
        PROPS_CATEGORIES.put("Network", Arrays.asList(
                "server-ip", "server-port", "motd", "max-players", "online-mode",
                "network-compression-threshold", "player-idle-timeout",
                "prevent-proxy-connections", "rate-limit", "max-tick-time"
        ));
        PROPS_CATEGORIES.put("RCON & Query", Arrays.asList(
                "enable-rcon", "rcon.port", "rcon.password",
                "enable-query", "query.port"
        ));
        PROPS_CATEGORIES.put("Access & Ops", Arrays.asList(
                "white-list", "enforce-whitelist", "op-permission-level"
        ));
        PROPS_CATEGORIES.put("Features", Arrays.asList(
                "enable-command-block"
        ));
        PROPS_CATEGORIES.put("Resource Pack", Arrays.asList(
                "resource-pack", "resource-pack-sha1",
                "resource-pack-prompt", "require-resource-pack"
        ));
        PROPS_CATEGORIES.put("Modern & Misc", Arrays.asList(
                "enforce-secure-profile", "hide-online-players", "log-ips",
                "initial-enabled-packs", "initial-disabled-packs",
                "text-filtering-config", "enable-jmx-monitoring", "bungeecord"
        ));
    }

    /** One-line description for every standard server.properties key. */
    private static String serverPropDescription(String key) {
        switch (key) {
            case "allow-flight":               return "Allow flying in Survival (requires anti-cheat plugin)";
            case "allow-nether":               return "Allow players to travel to the Nether";
            case "bungeecord":                 return "Enable BungeeCord proxy support (Spigot only)";
            case "difficulty":                 return "World difficulty: peaceful / easy / normal / hard";
            case "enable-command-block":       return "Allow command blocks to run commands";
            case "enable-jmx-monitoring":      return "Expose JMX metrics for external monitoring tools";
            case "enable-query":               return "Enable GameSpy4 query protocol";
            case "enable-rcon":                return "Enable remote console (RCON) access";
            case "enforce-secure-profile":     return "Require players to have a Mojang-signed key (1.19+)";
            case "enforce-whitelist":          return "Kick non-whitelisted players when whitelist is toggled on";
            case "force-gamemode":             return "Reset players to default gamemode on every join";
            case "function-permission-level":  return "Permission level needed to run /function (1–4)";
            case "gamemode":                   return "Default gamemode for new players";
            case "generate-structures":        return "Generate structures (villages, strongholds, etc.)";
            case "generator-settings":         return "JSON generator settings (used with flat level-type)";
            case "hardcore":                   return "Hardcore mode — bans players on death";
            case "hide-online-players":        return "Hide the online player count from the server list";
            case "initial-disabled-packs":     return "Data packs disabled when creating a new world";
            case "initial-enabled-packs":      return "Data packs enabled when creating a new world";
            case "level-name":                 return "World folder name (default: world)";
            case "level-seed":                 return "Seed used when no level.dat exists (overridden by plugin)";
            case "level-type":                 return "World generator type";
            case "log-ips":                    return "Write player IP addresses to the server log";
            case "max-build-height":           return "Maximum Y build height";
            case "max-players":                return "Maximum simultaneous connected players";
            case "max-tick-time":              return "Watchdog threshold in ms (-1 to disable)";
            case "max-world-size":             return "Maximum world radius in blocks";
            case "motd":                       return "Message shown on the Minecraft server list";
            case "network-compression-threshold": return "Minimum packet size for compression (-1 = off)";
            case "online-mode":                return "Authenticate players with Mojang (false for offline servers)";
            case "op-permission-level":        return "Default permission level for ops (1–4)";
            case "player-idle-timeout":        return "Minutes of idling before kicking (0 = never)";
            case "prevent-proxy-connections":  return "Reject players connecting through a VPN or proxy";
            case "pvp":                        return "Allow player-vs-player damage";
            case "query.port":                 return "Port for the GameSpy4 query service";
            case "rate-limit":                 return "Max packets per second per connection (0 = off)";
            case "rcon.password":              return "Password required to authenticate with RCON";
            case "rcon.port":                  return "Port the RCON service listens on";
            case "require-resource-pack":      return "Kick players who decline the server resource pack";
            case "resource-pack":              return "URL of the server resource pack";
            case "resource-pack-prompt":       return "Text shown when prompting players to accept the pack";
            case "resource-pack-sha1":         return "SHA-1 hash of the resource pack for verification";
            case "server-ip":                  return "IP address to bind (leave blank for all interfaces)";
            case "server-port":                return "TCP port the server listens on (default: 25565)";
            case "simulation-distance":        return "Chunk radius where game logic is simulated (1–32)";
            case "spawn-animals":              return "Allow passive/neutral animal spawning";
            case "spawn-monsters":             return "Allow hostile mob spawning";
            case "spawn-npcs":                 return "Allow villager NPC spawning";
            case "spawn-protection":           return "Protected radius around spawn (0 = disabled)";
            case "sync-chunk-writes":          return "Write chunks synchronously — safer but slower";
            case "text-filtering-config":      return "Path to a text-filtering / profanity-filter config file";
            case "use-native-transport":       return "Use Netty native epoll transport on Linux for lower latency";
            case "view-distance":              return "Chunk view distance sent to each player (2–32)";
            case "white-list":                 return "Enable the server whitelist";
            default:                           return "";
        }
    }

    /** Tab-completion value hints for every standard server.properties key. */
    private static List<String> getServerPropValueCompletions(String key) {
        switch (key.toLowerCase()) {
            case "difficulty":
                return Arrays.asList("peaceful", "easy", "normal", "hard");
            case "gamemode":
                return Arrays.asList("survival", "creative", "adventure", "spectator");
            case "level-type":
                return Arrays.asList("minecraft:normal", "minecraft:flat",
                        "minecraft:large_biomes", "minecraft:amplified",
                        "minecraft:single_biome_surface");
            case "op-permission-level":
            case "function-permission-level":
                return Arrays.asList("1", "2", "3", "4");
            case "allow-flight":
            case "allow-nether":
            case "bungeecord":
            case "enable-command-block":
            case "enable-jmx-monitoring":
            case "enable-query":
            case "enable-rcon":
            case "enforce-secure-profile":
            case "enforce-whitelist":
            case "force-gamemode":
            case "generate-structures":
            case "hardcore":
            case "hide-online-players":
            case "log-ips":
            case "online-mode":
            case "prevent-proxy-connections":
            case "pvp":
            case "require-resource-pack":
            case "spawn-animals":
            case "spawn-monsters":
            case "spawn-npcs":
            case "sync-chunk-writes":
            case "use-native-transport":
            case "white-list":
                return Arrays.asList("true", "false");
            case "max-players":
                return Arrays.asList("10", "20", "30", "50", "100");
            case "view-distance":
            case "simulation-distance":
                return Arrays.asList("4", "6", "8", "10", "12", "16", "20", "32");
            case "spawn-protection":
                return Arrays.asList("0", "4", "8", "16", "32");
            case "max-world-size":
                return Arrays.asList("1000", "5000", "10000", "29999984");
            case "server-port":
                return Arrays.asList("25565", "25566");
            case "rcon.port":
                return Arrays.asList("25575");
            case "query.port":
                return Arrays.asList("25565");
            case "network-compression-threshold":
                return Arrays.asList("-1", "0", "256", "512");
            case "player-idle-timeout":
                return Arrays.asList("0", "5", "15", "30", "60");
            case "max-tick-time":
                return Arrays.asList("-1", "60000", "120000");
            case "rate-limit":
                return Arrays.asList("0", "500", "1000");
            case "max-build-height":
                return Arrays.asList("128", "256", "320");
            default:
                return Collections.emptyList();
        }
    }
}
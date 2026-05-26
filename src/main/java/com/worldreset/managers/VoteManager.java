package com.worldreset.managers;

import com.worldreset.WorldResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Logger;

/**
 * Manages community vote-to-reset ({@code /resetvote}).
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>A player with {@code worldreset.vote} runs {@code /resetvote}.</li>
 *   <li>If no vote is active and online player count ≥ {@code vote.min-players},
 *       a vote window opens.  All players are notified.</li>
 *   <li>Any player runs {@code /resetvote yes} to cast a yes-vote (once per vote).</li>
 *   <li>After {@code vote.vote-duration-seconds} the result is evaluated:
 *       {@code (yesVotes / onlinePlayers) * 100 ≥ vote.required-percent} triggers a reset.</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * <pre>
 * vote:
 *   enabled: false
 *   required-percent: 60
 *   vote-duration-seconds: 60
 *   min-players: 3
 * </pre>
 */
public class VoteManager implements CommandExecutor, TabCompleter {

    private static final String VOTE_PERM = "worldreset.vote";

    private final WorldResetPlugin plugin;
    private final Logger           log;

    // Active-vote state (null when idle)
    private UUID       initiatorUuid = null;
    private Set<UUID>  yesVotes      = null;
    private Set<UUID>  noVotes       = null;
    private BukkitTask voteTask      = null;

    /**
     * Captures the display names of every yes-voter from the most recently
     * completed vote.  Read by ResetManager.buildDiscordMessage() via
     * {@link #getLastVoterNames()}.  Cleared when a new vote starts or is cancelled.
     */
    private final List<String> lastVoterNames = new ArrayList<>();

    public VoteManager(WorldResetPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    /**
     * Returns an unmodifiable snapshot of the yes-voter display names from the
     * most recently completed (passing) vote.  Used by ResetManager to populate
     * {voter_list} and {vote_result} in Discord templates.
     */
    public List<String> getLastVoterNames() {
        return Collections.unmodifiableList(lastVoterNames);
    }

    // ── CommandExecutor ────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        if (!sender.hasPermission(VOTE_PERM)) {
            sender.sendMessage("§cYou don't have permission to use /resetvote.");
            return true;
        }

        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isVoteEnabled()) {
            sender.sendMessage("§c[VOTE] Community reset votes are not enabled on this server.");
            return true;
        }

        // /resetvote yes/no
        if (args.length >= 1) {
            String sub = args[0].toLowerCase();
            if (sub.equals("yes")) { handleCastVote(sender, true); return true; }
            if (sub.equals("no"))  { handleCastVote(sender, false); return true; }
        }

        // /resetvote  — start a new vote
        handleStartVote(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String sub = args[0].toLowerCase();
            if ("yes".startsWith(sub)) out.add("yes");
            if ("no".startsWith(sub))  out.add("no");
        }
        return out;
    }

    // ── Vote lifecycle ─────────────────────────────────────────────────────

    private void handleStartVote(CommandSender sender) {
        if (isVoteActive()) {
            sender.sendMessage("§e[VOTE] A reset vote is already in progress.");
            return;
        }

        if (plugin.getResetManager().isResetPending()) {
            sender.sendMessage("§c[VOTE] A reset is already in progress.");
            return;
        }

        ConfigManager cfg        = plugin.getConfigManager();
        int           online     = Bukkit.getOnlinePlayers().size();
        int           minPlayers = cfg.getVoteMinPlayers();

        if (online < minPlayers) {
            sender.sendMessage("§c[VOTE] Not enough players online (" + online + "/" + minPlayers + " required).");
            return;
        }

        // Open the vote
        initiatorUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        yesVotes      = new HashSet<>();
        noVotes       = new HashSet<>();

        // Initiator's own vote counts immediately
        if (sender instanceof Player) {
            yesVotes.add(((Player) sender).getUniqueId());
        }

        int duration = cfg.getVoteDurationSeconds();
        Bukkit.broadcastMessage(
                "§e[VOTE] §f" + sender.getName()
                + " §estarted a reset vote! Use the on-screen menu to vote. §7("
                + duration + "s)");

        // 🖼️ Open GUI for everyone
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.getMenuManager().openVoteMenu(p);
        }

        // Schedule evaluation after duration
        voteTask = Bukkit.getScheduler().runTaskLater(plugin, this::evaluateVote, (long) duration * 20L);
    }

    private void handleCastVote(CommandSender sender, boolean yes) {
        if (!isVoteActive()) {
            sender.sendMessage("§e[VOTE] No reset vote is currently active.");
            return;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§c[VOTE] Only players can cast a vote.");
            return;
        }

        UUID uuid = ((Player) sender).getUniqueId();
        if ((yesVotes != null && yesVotes.contains(uuid)) || (noVotes != null && noVotes.contains(uuid))) {
            sender.sendMessage("§e[VOTE] You have already voted.");
            return;
        }

        if (yes) yesVotes.add(uuid);
        else     noVotes.add(uuid);

        int online = Bukkit.getOnlinePlayers().size();
        Bukkit.broadcastMessage(
                "§e[VOTE] §f" + sender.getName()
                + " §evoted " + (yes ? "§aYES" : "§cNO") + "§e! §7(" 
                + (yesVotes.size() + noVotes.size()) + "/" + online + ")");
    }

    private void evaluateVote() {
        int online  = Bukkit.getOnlinePlayers().size();
        int yes     = (yesVotes == null) ? 0 : yesVotes.size();
        int no      = (noVotes == null) ? 0 : noVotes.size();
        int required = plugin.getConfigManager().getVoteRequiredPercent();

        // Ensure we don't divide by zero on an empty server
        double pct = (online > 0) ? (yes * 100.0 / online) : 0.0;

        // Capture voter display-names BEFORE clearVoteState() nulls out yesVotes
        lastVoterNames.clear();
        if (yesVotes != null) {
            for (UUID uid : yesVotes) {
                Player p = Bukkit.getPlayer(uid);
                lastVoterNames.add(p != null ? p.getName()
                        : uid.toString().substring(0, 8) + "…");
            }
        }

        clearVoteState();

        if (pct >= required) {
            Bukkit.broadcastMessage(
                    "§a[VOTE] Reset vote passed! §7(" + yes + " YES, " + no + " NO / " + online + " online"
                    + ", " + String.format("%.0f", pct) + "% ≥ " + required + "%)");
            plugin.getResetManager().startReset("Vote", false);
        } else {
            Bukkit.broadcastMessage(
                    "§c[VOTE] Reset vote failed! §7(" + yes + " YES, " + no + " NO / " + online + " online"
                    + ", " + String.format("%.0f", pct) + "% < " + required + "%)");
        }
    }

    /** Cancel any active vote (called on plugin disable or when a manual reset starts). */
    public void cancelVote() {
        if (!isVoteActive()) return;
        lastVoterNames.clear(); // cancelled vote — no names to report
        clearVoteState();
        Bukkit.broadcastMessage("§e[VOTE] The reset vote has been cancelled.");
    }

    public boolean isVoteActive() {
        return voteTask != null;
    }

    private void clearVoteState() {
        if (voteTask != null) {
            voteTask.cancel();
            voteTask = null;
        }
        yesVotes      = null;
        noVotes       = null;
        initiatorUuid = null;
    }
}
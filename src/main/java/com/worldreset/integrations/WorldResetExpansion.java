package com.worldreset.integrations;

import com.worldreset.WorldResetPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class WorldResetExpansion extends PlaceholderExpansion {

    private final WorldResetPlugin plugin;

    public WorldResetExpansion(WorldResetPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "worldreset";
    }

    @Override
    public @NotNull String getAuthor() {
        return "WorldResetPlugin";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // This is required or else PlaceholderAPI will unregister it on reload
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("time_left")) {
            int left = plugin.getResetManager().getSecondsLeft();
            return left > 0 ? String.valueOf(left) : "0";
        }

        if (params.equalsIgnoreCase("is_pending")) {
            return String.valueOf(plugin.getResetManager().isResetPending());
        }

        if (params.equalsIgnoreCase("next_schedule")) {
            String desc = plugin.getScheduleManager().getNextFireDescription();
            return desc.isEmpty() ? "None" : desc;
        }

        return null;
    }
}
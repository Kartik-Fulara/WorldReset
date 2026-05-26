package com.worldreset.integrations;

import com.worldreset.WorldResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Handles detection and hooks for external plugins like Multiverse and FAWE.
 */
public class IntegrationManager {

    private final WorldResetPlugin plugin;
    private MultiverseIntegration mv = null;
    private FaweIntegration       fawe = null;

    public IntegrationManager(WorldResetPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        Plugin mvPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (mvPlugin != null && mvPlugin.isEnabled()) {
            mv = new MultiverseIntegration(plugin);
            plugin.getLogger().info("Hooked into Multiverse-Core for world management.");
        }

        Plugin wePlugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (wePlugin != null && wePlugin.isEnabled()) {
             fawe = new FaweIntegration(plugin);
             plugin.getLogger().info("Hooked into WorldEdit/FAWE for region resets.");
        }
    }

    public MultiverseIntegration getMultiverse() { return mv; }
    public FaweIntegration       getFawe()       { return fawe; }
    
    public boolean hasMultiverse() { return mv != null; }
    public boolean hasFawe()       { return fawe != null; }
}

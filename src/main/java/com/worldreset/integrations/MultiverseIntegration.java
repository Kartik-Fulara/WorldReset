package com.worldreset.integrations;

import com.onarandombox.MultiverseCore.MultiverseCore;
import com.onarandombox.MultiverseCore.api.MVWorldManager;
import com.onarandombox.MultiverseCore.api.MultiverseWorld;
import com.worldreset.WorldResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Handles communication with Multiverse-Core.
 */
public class MultiverseIntegration {

    private final WorldResetPlugin plugin;
    private final MultiverseCore core;

    public MultiverseIntegration(WorldResetPlugin plugin) {
        this.plugin = plugin;
        this.core = (MultiverseCore) Bukkit.getPluginManager().getPlugin("Multiverse-Core");
    }

    public void unregisterWorld(String worldName) {
        if (core == null) return;
        MVWorldManager wm = core.getMVWorldManager();
        if (wm.isMVWorld(worldName)) {
            wm.removeWorldFromConfig(worldName);
        }
    }

    public void registerWorld(String worldName, World.Environment env) {
        if (core == null) return;
        MVWorldManager wm = core.getMVWorldManager();
        if (!wm.isMVWorld(worldName)) {
            wm.addWorld(worldName, env, null, null, null, null);
        }
    }
}

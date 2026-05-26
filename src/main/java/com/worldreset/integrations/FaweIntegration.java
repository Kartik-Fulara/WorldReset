package com.worldreset.integrations;

import com.fastasyncworldedit.core.FaweAPI;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.worldreset.WorldResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Handles high-performance region resets using FastAsyncWorldEdit (FAWE).
 */
public class FaweIntegration {

    private final WorldResetPlugin plugin;

    public FaweIntegration(WorldResetPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Resets a specific region by pasting a schematic at a specific location.
     * 
     * @param worldName     World to paste into
     * @param schematicName Filename in /plugins/WorldResetPlugin/schematics/
     * @param x, y, z       Target coordinates
     */
    public void resetRegion(String worldName, String schematicName, int x, int y, int z) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("[FAWE] Cannot reset region: World '" + worldName + "' not found.");
            return;
        }

        File schemFile = new File(plugin.getDataFolder(), "schematics/" + schematicName);
        if (!schemFile.exists()) {
            plugin.getLogger().warning("[FAWE] Schematic NOT FOUND: " + schemFile.getAbsolutePath());
            return;
        }

        ClipboardFormat format = ClipboardFormats.findByFile(schemFile);
        if (format == null) {
            plugin.getLogger().warning("[FAWE] Unknown schematic format: " + schematicName);
            return;
        }

        try (ClipboardReader reader = format.getReader(new FileInputStream(schemFile))) {
            Clipboard clipboard = reader.read();
            
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                Operation operation = new ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(BlockVector3.at(x, y, z))
                        .ignoreAirBlocks(false)
                        .build();
                
                Operations.complete(operation);
                plugin.getLogger().info("[FAWE] Successfully reset region in '" + worldName + "' using '" + schematicName + "' at " + x + "," + y + "," + z);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("[FAWE] Failed to read/paste schematic: " + e.getMessage());
        }
    }
}

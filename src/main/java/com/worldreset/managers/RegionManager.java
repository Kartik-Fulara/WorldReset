package com.worldreset.managers;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.*;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.worldreset.api.IRegionManager;
import com.worldreset.WorldResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Handles selective region preservation using WorldEdit schematics.
 */
public class RegionManager implements IRegionManager {

    private final WorldResetPlugin plugin;
    private final Logger log;
    private final File schematicsDir;

    public RegionManager(WorldResetPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.schematicsDir = new File(plugin.getDataFolder(), "schematics");
        if (!schematicsDir.exists()) schematicsDir.mkdirs();
    }

    /**
     * Saves a cuboid region to a schematic file.
     */
    public boolean saveRegion(String name, Location min, Location max) {
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) return false;

        World world = min.getWorld();
        if (world == null) return false;

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        CuboidRegion region = new CuboidRegion(weWorld, BukkitAdapter.asBlockVector(min), BukkitAdapter.asBlockVector(max));
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
            ForwardExtentCopy forwardExtentCopy = new ForwardExtentCopy(
                    editSession, region, clipboard, region.getMinimumPoint()
            );
            Operations.complete(forwardExtentCopy);
        } catch (WorldEditException e) {
            log.severe("Failed to copy region to clipboard: " + e.getMessage());
            return false;
        }

        File file = new File(schematicsDir, name + ".schem");
        try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(new FileOutputStream(file))) {
            writer.write(clipboard);
            return true;
        } catch (IOException e) {
            log.severe("Failed to save schematic " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Pastes a schematic back into the world.
     */
    public boolean pasteRegion(String name, Location target) {
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) return false;

        File file = new File(schematicsDir, name + ".schem");
        if (!file.exists()) return false;

        Clipboard clipboard;
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) return false;

        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            clipboard = reader.read();
        } catch (IOException e) {
            log.severe("Failed to read schematic " + file.getName() + ": " + e.getMessage());
            return false;
        }

        World world = target.getWorld();
        if (world == null) return false;

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(BukkitAdapter.asBlockVector(target))
                    .ignoreAirBlocks(false)
                    .build();
            Operations.complete(operation);
            return true;
        } catch (WorldEditException e) {
            log.severe("Failed to paste schematic " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }
}
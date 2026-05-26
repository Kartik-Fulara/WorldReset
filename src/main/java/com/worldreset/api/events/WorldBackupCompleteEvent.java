package com.worldreset.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * Fired after a world backup has successfully completed.
 */
public class WorldBackupCompleteEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final List<String> worlds;
    private final String zipName;
    private final long sizeMb;

    public WorldBackupCompleteEvent(List<String> worlds, String zipName, long sizeMb) {
        this.worlds = worlds;
        this.zipName = zipName;
        this.sizeMb = sizeMb;
    }

    public List<String> getWorlds() { return worlds; }
    public String getZipName() { return zipName; }
    public long getSizeMb() { return sizeMb; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
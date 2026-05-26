package com.worldreset.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a backup restoration has successfully completed.
 */
public class WorldRestoreCompleteEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final String zipName;
    private final long durationSecs;

    public WorldRestoreCompleteEvent(String zipName, long durationSecs) {
        this.zipName = zipName;
        this.durationSecs = durationSecs;
    }

    public String getZipName() { return zipName; }
    public long getDurationSecs() { return durationSecs; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
package com.worldreset.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * Fired immediately before a world backup starts.
 */
public class WorldBackupStartEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final String initiator;
    private final List<String> worlds;

    public WorldBackupStartEvent(String initiator, List<String> worlds) {
        this.initiator = initiator;
        this.worlds = worlds;
    }

    public String getInitiator() { return initiator; }
    public List<String> getWorlds() { return worlds; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
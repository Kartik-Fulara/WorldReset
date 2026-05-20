package com.worldreset.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * Fired when a world reset has successfully completed.
 */
public class WorldResetCompleteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String initiator;
    private final String mode;
    private final List<String> worlds;
    private final long durationSecs;

    public WorldResetCompleteEvent(String initiator, String mode, List<String> worlds, long durationSecs) {
        this.initiator = initiator;
        this.mode = mode;
        this.worlds = worlds;
        this.durationSecs = durationSecs;
    }

    public String getInitiator() {
        return initiator;
    }

    public String getMode() {
        return mode;
    }

    public List<String> getWorlds() {
        return worlds;
    }

    public long getDurationSecs() {
        return durationSecs;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
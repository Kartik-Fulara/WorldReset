package com.worldreset.api.events;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * Fired when a world reset is about to start (immediately after the countdown ends or when forced).
 * This event is cancellable.
 */
public class WorldResetStartEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled;
    private final String initiator;
    private final List<String> worlds;

    public WorldResetStartEvent(String initiator, List<String> worlds) {
        this.initiator = initiator;
        this.worlds = worlds;
    }

    public String getInitiator() {
        return initiator;
    }

    public List<String> getWorlds() {
        return worlds;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
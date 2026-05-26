package com.worldreset.api.events;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired immediately before a backup restoration starts.
 */
public class WorldRestoreStartEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final String initiator;
    private final String zipName;
    private boolean cancelled = false;

    public WorldRestoreStartEvent(String initiator, String zipName) {
        this.initiator = initiator;
        this.zipName = zipName;
    }

    public String getInitiator() { return initiator; }
    public String getZipName() { return zipName; }

    @Override
    public boolean isCancelled() { return cancelled; }
    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
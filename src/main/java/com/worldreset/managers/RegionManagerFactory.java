package com.worldreset.managers;

import com.worldreset.WorldResetPlugin;
import com.worldreset.api.IRegionManager;

/**
 * Factory for creating IRegionManager instances.
 * This class isolates the loading of RegionManager (which depends on WorldEdit)
 * so it only occurs when WorldEdit is confirmed to be present.
 */
public class RegionManagerFactory {
    /**
     * Creates a new RegionManager instance.
     *
     * @param plugin The WorldResetPlugin instance
     * @return A new IRegionManager
     */
    public static IRegionManager create(WorldResetPlugin plugin) {
        return new RegionManager(plugin);
    }
}

package com.worldreset.api;

import org.bukkit.Location;

/**
 * Interface for managing region preservation, allowing for optional WorldEdit integration.
 */
public interface IRegionManager {
    /**
     * Saves a cuboid region to a schematic file.
     *
     * @param name Name of the schematic
     * @param min Minimum corner location
     * @param max Maximum corner location
     * @return true if successful
     */
    boolean saveRegion(String name, Location min, Location max);

    /**
     * Pastes a schematic back into the world.
     *
     * @param name Name of the schematic
     * @param target Target location (minimum corner)
     * @return true if successful
     */
    boolean pasteRegion(String name, Location target);
}

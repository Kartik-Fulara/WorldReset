package com.worldreset.gui;

import com.worldreset.WorldResetPlugin;
import com.worldreset.managers.ResetManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class MenuManager implements Listener {

    private final WorldResetPlugin plugin;
    private static final String MENU_TITLE = "§0WorldReset Dashboard";

    public MenuManager(WorldResetPlugin plugin) {
        this.plugin = plugin;
    }

    public void openDashboard(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE);

        ResetManager rm = plugin.getResetManager();

        // Start Reset
        inv.setItem(11, createItem(Material.GREEN_WOOL, "§a§lStart Reset", 
                "§7Click to initiate a world reset.", 
                "§7(Requires confirmation in chat)"));

        // Status
        String status = rm.isResetPending() ? "§c§lPENDING (" + rm.getSecondsLeft() + "s)" : "§a§lIDLE";
        inv.setItem(13, createItem(Material.COMPASS, "§fStatus: " + status, 
                "§7Current reset state."));

        // Cancel Reset
        inv.setItem(15, createItem(Material.RED_WOOL, "§c§lCancel Reset", 
                "§7Click to abort a pending reset."));

        // Info / Reload
        inv.setItem(26, createItem(Material.LEVER, "§eReload Plugin", 
                "§7Click to reload all configurations."));

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(MENU_TITLE)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || clicked.getType() == Material.AIR) return;

        switch (event.getRawSlot()) {
            case 11: // Start
                player.performCommand("worldreset start");
                player.closeInventory();
                break;
            case 13: // Status
                player.performCommand("worldreset status");
                break;
            case 15: // Cancel
                player.performCommand("worldreset cancel");
                player.closeInventory();
                break;
            case 26: // Reload
                player.performCommand("worldreset reload");
                player.closeInventory();
                break;
        }
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}
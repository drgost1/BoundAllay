package dev.nafis.boundallay.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Marker holder for the vault GUI. Tracks whether we're on the list screen
 * or the manage screen, and maps slots to allay IDs.
 */
public class AllayVaultHolder implements InventoryHolder {

    private Inventory inventory;

    /** True if this is the list screen (showing all allays). False if manage screen. */
    private boolean listScreen = true;

    /** The selected allay ID when on the manage screen. */
    private String selectedAllay;

    /** Maps inventory slot numbers to allay IDs (used on list screen). */
    private final Map<Integer, String> slotToAllay = new HashMap<>();

    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @NotNull
    @Override
    public Inventory getInventory() { return inventory; }

    public boolean isListScreen() { return listScreen; }
    public void setListScreen(boolean listScreen) { this.listScreen = listScreen; }

    public String getSelectedAllay() { return selectedAllay; }
    public void setSelectedAllay(String selectedAllay) { this.selectedAllay = selectedAllay; }

    public Map<Integer, String> getSlotToAllay() { return slotToAllay; }
}

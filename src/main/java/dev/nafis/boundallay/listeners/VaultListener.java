package dev.nafis.boundallay.listeners;

import dev.nafis.boundallay.AllayData;
import dev.nafis.boundallay.AllayManager;
import dev.nafis.boundallay.AllayType;
import dev.nafis.boundallay.gui.AllayVaultGUI;
import dev.nafis.boundallay.gui.AllayVaultHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class VaultListener implements Listener {

    private final AllayManager mgr;

    public VaultListener(AllayManager mgr) {
        this.mgr = mgr;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof AllayVaultHolder holder)) return;

        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (holder.isListScreen()) {
            handleListClick(p, holder, slot);
        } else {
            handleManageClick(p, holder, slot);
        }
    }

    private void handleListClick(Player player, AllayVaultHolder holder, int slot) {
        if (slot < 0 || slot >= AllayVaultGUI.LIST_SIZE) return;

        String allayId = holder.getSlotToAllay().get(slot);
        if (allayId != null) {
            player.closeInventory();
            AllayVaultGUI.openManage(player, mgr, allayId);
            return;
        }

        switch (slot) {
            case AllayVaultGUI.LIST_BIND_SLOT -> {
                player.closeInventory();
                mgr.bind(player);
            }
            case AllayVaultGUI.LIST_CLOSE_SLOT -> player.closeInventory();
            default -> {}
        }
    }

    private void handleManageClick(Player player, AllayVaultHolder holder, int slot) {
        if (slot < 0 || slot >= AllayVaultGUI.MANAGE_SIZE) return;

        String allayId = holder.getSelectedAllay();
        if (allayId == null) {
            player.closeInventory();
            return;
        }

        switch (slot) {
            case AllayVaultGUI.MANAGE_SUMMON_SLOT -> {
                player.closeInventory();
                mgr.summon(player, allayId);
            }
            case AllayVaultGUI.MANAGE_STORE_SLOT -> {
                player.closeInventory();
                mgr.store(player, allayId);
            }
            case AllayVaultGUI.MANAGE_RELEASE_SLOT -> {
                player.closeInventory();
                mgr.release(player, allayId);
            }
            case AllayVaultGUI.MANAGE_TYPE_SLOT -> {
                AllayData data = mgr.getStorage().get(player.getUniqueId(), allayId);
                if (data != null) {
                    AllayType newType = data.getType().next();
                    mgr.setType(player, allayId, newType);
                    AllayVaultGUI.openManage(player, mgr, allayId);
                }
            }
            case AllayVaultGUI.MANAGE_BACK_SLOT -> {
                player.closeInventory();
                AllayVaultGUI.openList(player, mgr);
            }
            case AllayVaultGUI.MANAGE_CLOSE_SLOT -> player.closeInventory();
            case AllayVaultGUI.MANAGE_INFO_SLOT -> {}
            default -> {}
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof AllayVaultHolder) {
            e.setCancelled(true);
        }
    }
}

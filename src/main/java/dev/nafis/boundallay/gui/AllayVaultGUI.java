package dev.nafis.boundallay.gui;

import dev.nafis.boundallay.AllayData;
import dev.nafis.boundallay.AllayManager;
import dev.nafis.boundallay.AllayType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AllayVaultGUI {

    public static final int LIST_SIZE = 54;
    public static final int LIST_BIND_SLOT = 49;
    public static final int LIST_CLOSE_SLOT = 53;

    public static final int MANAGE_SIZE = 27;
    public static final int MANAGE_INFO_SLOT = 4;
    public static final int MANAGE_SUMMON_SLOT = 11;
    public static final int MANAGE_STORE_SLOT = 13;
    public static final int MANAGE_RELEASE_SLOT = 15;
    public static final int MANAGE_TYPE_SLOT = 22;
    public static final int MANAGE_BACK_SLOT = 18;
    public static final int MANAGE_CLOSE_SLOT = 26;

    private AllayVaultGUI() {}

    // ==================== LIST SCREEN ====================

    public static void openList(Player player, AllayManager mgr) {
        AllayVaultHolder holder = new AllayVaultHolder();
        holder.setListScreen(true);

        Inventory inv = Bukkit.createInventory(
                holder, LIST_SIZE,
                Component.text("Allay Vault").color(NamedTextColor.DARK_AQUA)
        );
        holder.setInventory(inv);

        ItemStack glass = filler();
        for (int i = 0; i < LIST_SIZE; i++) inv.setItem(i, glass);

        Map<String, AllayData> allays = mgr.getStorage().getAll(player.getUniqueId());
        int maxAllays = mgr.getMaxAllays();

        int[] contentSlots = getContentSlots();
        int slotIndex = 0;

        for (Map.Entry<String, AllayData> entry : allays.entrySet()) {
            if (slotIndex >= contentSlots.length) break;
            int slot = contentSlots[slotIndex];
            String allayId = entry.getKey();
            AllayData data = entry.getValue();

            holder.getSlotToAllay().put(slot, allayId);
            inv.setItem(slot, allayListItem(data));
            slotIndex++;
        }

        boolean canBind = allays.size() < maxAllays;
        inv.setItem(LIST_BIND_SLOT, bindButton(canBind, allays.size(), maxAllays));
        inv.setItem(4, headerItem(allays.size(), maxAllays));
        inv.setItem(LIST_CLOSE_SLOT, closeButton());

        player.openInventory(inv);
    }

    private static int[] getContentSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots.add(row * 9 + col);
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private static ItemStack allayListItem(AllayData data) {
        AllayType type = data.getType();
        Material mat = data.active ? type.icon() : Material.ALLAY_SPAWN_EGG;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        NamedTextColor nameColor = data.active ? AllayManager.typeColor(type) : NamedTextColor.AQUA;
        meta.displayName(Component.text(data.displayName())
                .color(nameColor)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line("Type: " + type.displayName(), AllayManager.typeColor(type)));
        lore.add(line("Status: " + (data.active ? "Summoned" : "Stored"),
                data.active ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        lore.add(line("Bonded item: " + (data.heldItemB64 != null ? "Yes" : "No"), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(line("Click to manage", NamedTextColor.YELLOW));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack headerItem(int count, int max) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Your Allays (" + count + "/" + max + ")")
                .color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Click an allay to manage it.", NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack bindButton(boolean canBind, int count, int max) {
        return labeled(
                canBind ? Material.LEAD : Material.GRAY_DYE,
                canBind ? "Bind Nearby Allay" : "Limit Reached (" + count + "/" + max + ")",
                canBind ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.DARK_GRAY,
                canBind
                        ? List.of("Stand within 5 blocks of a", "wild allay, then click this.", "Name will be auto-generated.")
                        : List.of("Release an allay first to bind a new one.")
        );
    }

    // ==================== MANAGE SCREEN ====================

    public static void openManage(Player player, AllayManager mgr, String allayId) {
        AllayData data = mgr.getStorage().get(player.getUniqueId(), allayId);
        if (data == null) {
            AllayManager.msg(player, "That allay no longer exists.", NamedTextColor.RED);
            return;
        }

        AllayVaultHolder holder = new AllayVaultHolder();
        holder.setListScreen(false);
        holder.setSelectedAllay(allayId);

        Inventory inv = Bukkit.createInventory(
                holder, MANAGE_SIZE,
                Component.text("Manage: " + data.displayName()).color(NamedTextColor.DARK_AQUA)
        );
        holder.setInventory(inv);

        ItemStack glass = filler();
        for (int i = 0; i < MANAGE_SIZE; i++) inv.setItem(i, glass);

        inv.setItem(MANAGE_INFO_SLOT, infoItem(data));

        boolean canSummon = !data.active;
        inv.setItem(MANAGE_SUMMON_SLOT, labeled(
                canSummon ? Material.EMERALD : Material.GRAY_DYE,
                canSummon ? "Summon" : "Already Summoned",
                canSummon ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY,
                canSummon
                        ? List.of("Click to spawn this allay", "at your current location.")
                        : List.of("This allay is already in the world.")
        ));

        boolean canStore = data.active;
        inv.setItem(MANAGE_STORE_SLOT, labeled(
                canStore ? Material.ENDER_CHEST : Material.GRAY_DYE,
                canStore ? "Store" : "Not Summoned",
                canStore ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY,
                canStore
                        ? List.of("Click to safely store", "this allay in the vault.")
                        : List.of("This allay is not currently in the world.")
        ));

        inv.setItem(MANAGE_RELEASE_SLOT, labeled(
                Material.TNT,
                "Release (Unbind)",
                NamedTextColor.RED,
                List.of("Permanently unbind this allay.", "This cannot be undone!")
        ));

        AllayType current = data.getType();
        AllayType next = current.next();
        inv.setItem(MANAGE_TYPE_SLOT, labeled(
                current.icon(),
                "Type: " + current.displayName(),
                AllayManager.typeColor(current),
                List.of(current.description(), "", "Click to switch to " + next.displayName())
        ));

        inv.setItem(MANAGE_BACK_SLOT, labeled(
                Material.ARROW,
                "Back to List",
                NamedTextColor.WHITE,
                List.of()
        ));

        inv.setItem(MANAGE_CLOSE_SLOT, closeButton());

        player.openInventory(inv);
    }

    private static ItemStack infoItem(AllayData data) {
        AllayType type = data.getType();
        ItemStack item = new ItemStack(Material.ALLAY_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(data.displayName())
                .color(AllayManager.typeColor(type))
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Type: " + type.displayName(), AllayManager.typeColor(type)));
        lore.add(line("Status: " + (data.active ? "Summoned" : "Stored"),
                data.active ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        lore.add(line("Bonded item: " + (data.heldItemB64 != null ? "Yes" : "No"), NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ==================== SHARED HELPERS ====================

    private static ItemStack filler() {
        ItemStack g = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = g.getItemMeta();
        m.displayName(Component.text(" "));
        g.setItemMeta(m);
        return g;
    }

    private static ItemStack closeButton() {
        return labeled(Material.BARRIER, "Close", NamedTextColor.RED, List.of());
    }

    static ItemStack labeled(Material mat, String name, NamedTextColor color, List<String> loreLines) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        m.displayName(Component.text(name).color(color)
                .decoration(TextDecoration.ITALIC, false));
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) {
                if (l.isEmpty()) lore.add(Component.empty());
                else lore.add(line(l, NamedTextColor.GRAY));
            }
            m.lore(lore);
        }
        i.setItemMeta(m);
        return i;
    }

    private static Component line(String s, NamedTextColor c) {
        return Component.text(s).color(c).decoration(TextDecoration.ITALIC, false);
    }
}

package dev.nafis.boundallay.gui;

import dev.nafis.boundallay.AllayData;
import dev.nafis.boundallay.AllayType;
import dev.nafis.boundallay.AllayManager;
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

/**
 * Two-screen chest GUI for the bound allay vault.
 *
 * Screen 1 (List): Shows all bound allays as clickable items. Click one to manage it.
 * Screen 2 (Manage): Shows actions for a single allay (summon, store, release, back).
 *
 * No Unicode symbols, short lore, basic materials - Bedrock compatible via Geyser.
 */
public final class AllayVaultGUI {

    // List screen constants
    public static final int LIST_SIZE = 54; // 6 rows
    public static final int LIST_BIND_SLOT = 49;   // bottom row: bind new
    public static final int LIST_SUMMONALL_SLOT = 50;
    public static final int LIST_STOREALL_SLOT = 51;
    public static final int LIST_CLOSE_SLOT = 53;  // bottom row: close

    // Manage screen constants
    public static final int MANAGE_SIZE = 27; // 3 rows
    public static final int MANAGE_INFO_SLOT = 4;
    public static final int MANAGE_SUMMON_SLOT = 11;
    public static final int MANAGE_STORE_SLOT = 13;
    public static final int MANAGE_RELEASE_SLOT = 15;
    public static final int MANAGE_BACK_SLOT = 18;
    public static final int MANAGE_RENAME_SLOT = 20;
    public static final int MANAGE_CLOSE_SLOT = 26;
    public static final int MANAGE_TYPE_SLOT = 22;
    public static final int MANAGE_GUARD_SLOT = 24;
    public static final int MANAGE_PVP_SLOT = 8;

    private AllayVaultGUI() {}

    // ==================== LIST SCREEN ====================

    /**
     * Open the list screen showing all allays for the player.
     */
    public static void openList(Player player, AllayManager mgr) {
        AllayVaultHolder holder = new AllayVaultHolder();
        holder.setListScreen(true);

        Inventory inv = Bukkit.createInventory(
                holder, LIST_SIZE,
                Component.text("Allay Vault").color(NamedTextColor.DARK_AQUA)
        );
        holder.setInventory(inv);

        // Fill border
        ItemStack glass = filler();
        for (int i = 0; i < LIST_SIZE; i++) inv.setItem(i, glass);

        // Populate allays in slots 10-43 (inner area of rows 1-4, skipping borders)
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

        // Bind button - only if under max
        boolean canBind = allays.size() < maxAllays;
        inv.setItem(LIST_BIND_SLOT, bindButton(canBind, allays.size(), maxAllays));

        // Info in top middle
        inv.setItem(4, headerItem(allays.size(), maxAllays));

        // Close button
        // Summon All button
        inv.setItem(LIST_SUMMONALL_SLOT, labeled(
                Material.EMERALD_BLOCK,
                "Summon All",
                NamedTextColor.GREEN,
                List.of("Summon all stored allays.")
        ));

        // Store All button
        inv.setItem(LIST_STOREALL_SLOT, labeled(
                Material.ENDER_CHEST,
                "Store All",
                NamedTextColor.GOLD,
                List.of("Store all active allays.")
        ));
        inv.setItem(LIST_CLOSE_SLOT, closeButton());

        player.openInventory(inv);
    }

    /** Get the inner content slot indices for a 54-slot inventory (rows 1-4, columns 1-7). */
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
        Material mat = data.active ? Material.DIAMOND : Material.ALLAY_SPAWN_EGG;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        NamedTextColor nameColor = data.active ? NamedTextColor.GREEN : NamedTextColor.AQUA;
        meta.displayName(Component.text(data.displayName())
                .color(nameColor)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        AllayType listType = AllayType.fromString(data.type);
        lore.add(line("Type: " + listType.getDisplayName(), NamedTextColor.LIGHT_PURPLE));
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

    /**
     * Open the manage screen for a specific allay.
     */
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

        // Fill with glass
        ItemStack glass = filler();
        for (int i = 0; i < MANAGE_SIZE; i++) inv.setItem(i, glass);

        // Info display
        inv.setItem(MANAGE_INFO_SLOT, infoItem(data));

        // Summon button
        boolean canSummon = !data.active;
        inv.setItem(MANAGE_SUMMON_SLOT, labeled(
                canSummon ? Material.EMERALD : Material.GRAY_DYE,
                canSummon ? "Summon" : "Already Summoned",
                canSummon ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY,
                canSummon
                        ? List.of("Click to spawn this allay", "at your current location.")
                        : List.of("This allay is already in the world.")
        ));

        // Store button
        boolean canStore = data.active;
        inv.setItem(MANAGE_STORE_SLOT, labeled(
                canStore ? Material.ENDER_CHEST : Material.GRAY_DYE,
                canStore ? "Store" : "Not Summoned",
                canStore ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY,
                canStore
                        ? List.of("Click to safely store", "this allay in the vault.")
                        : List.of("This allay is not currently in the world.")
        ));

        // Release button
        inv.setItem(MANAGE_RELEASE_SLOT, labeled(
                Material.TNT,
                "Release (Unbind)",
                NamedTextColor.RED,
                List.of("Permanently unbind this allay.", "This cannot be undone!")
        ));

        // Rename button
        inv.setItem(MANAGE_RENAME_SLOT, labeled(
                Material.NAME_TAG,
                "Rename",
                NamedTextColor.YELLOW,
                List.of("Click to rename this allay.", "Use: /allay rename old new")
        ));

        // Type button
        AllayType allayType = AllayType.fromString(data.type);
        inv.setItem(MANAGE_TYPE_SLOT, labeled(
                Material.DIAMOND_SWORD,
                "Type: " + allayType.getDisplayName(),
                NamedTextColor.LIGHT_PURPLE,
                List.of(allayType.getDescription(), "", "Click to cycle type")
        ));

        // Guard button
        boolean isGuardian = allayType == AllayType.GUARDIAN;
        inv.setItem(MANAGE_GUARD_SLOT, labeled(
                Material.SHIELD,
                isGuardian ? ("Guard: " + (data.guardMode ? "ON" : "OFF")) : "Guard (Guardian only)",
                isGuardian ? (data.guardMode ? NamedTextColor.GREEN : NamedTextColor.GOLD) : NamedTextColor.DARK_GRAY,
                isGuardian
                        ? List.of(data.guardMode ? "Guarding a location" : "Not guarding", "", "Click to toggle guard mode")
                        : List.of("Set type to Guardian first")
        ));


        // PvP button
        inv.setItem(MANAGE_PVP_SLOT, labeled(
                Material.IRON_SWORD,
                "PvP: " + (data.pvpMode ? "ON" : "OFF"),
                data.pvpMode ? NamedTextColor.GREEN : NamedTextColor.RED,
                List.of(data.pvpMode ? "Allay attacks players who hit you" : "Allay ignores player attackers", "", "Click to toggle")
        ));

        // Back button
        inv.setItem(MANAGE_BACK_SLOT, labeled(
                Material.ARROW,
                "Back to List",
                NamedTextColor.WHITE,
                List.of()
        ));

        // Close button
        inv.setItem(MANAGE_CLOSE_SLOT, closeButton());

        player.openInventory(inv);
    }

    private static ItemStack infoItem(AllayData data) {
        ItemStack item = new ItemStack(Material.ALLAY_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(data.displayName())
                .color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        AllayType infoType = AllayType.fromString(data.type);
        lore.add(line("ID: " + data.id, NamedTextColor.DARK_GRAY));
        lore.add(line("Type: " + infoType.getDisplayName(), NamedTextColor.LIGHT_PURPLE));
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

    static ItemStack labeled(Material mat, String name, NamedTextColor color,
                                     List<String> loreLines) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        m.displayName(Component.text(name).color(color)
                .decoration(TextDecoration.ITALIC, false));
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(line(l, NamedTextColor.GRAY));
            m.lore(lore);
        }
        i.setItemMeta(m);
        return i;
    }

    private static Component line(String s, NamedTextColor c) {
        return Component.text(s).color(c).decoration(TextDecoration.ITALIC, false);
    }
}

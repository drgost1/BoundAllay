package dev.nafis.boundallay;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BedrockMenu {

    private static Boolean floodgateLoaded = null;

    private BedrockMenu() {}

    public static boolean shouldUseForm(UUID uuid) {
        if (!isFloodgateLoaded()) return false;
        try {
            return org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isFloodgateLoaded() {
        if (floodgateLoaded != null) return floodgateLoaded;
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgateLoaded = Bukkit.getPluginManager().getPlugin("floodgate") != null;
        } catch (ClassNotFoundException e) {
            floodgateLoaded = false;
        }
        return floodgateLoaded;
    }

    // ==================== LIST FORM ====================

    public static void sendListForm(Player player, AllayManager mgr) {
        try {
            doSendListForm(player, mgr);
        } catch (Throwable t) {
            mgr.getPlugin().getLogger().warning(
                    "Floodgate list form failed, falling back to chest GUI: " + t.getMessage());
            dev.nafis.boundallay.gui.AllayVaultGUI.openList(player, mgr);
        }
    }

    private static void doSendListForm(Player player, AllayManager mgr) {
        UUID owner = player.getUniqueId();
        Map<String, AllayData> allays = mgr.getStorage().getAll(owner);
        int maxAllays = mgr.getMaxAllays();

        StringBuilder content = new StringBuilder();
        content.append("Your Allays (").append(allays.size()).append("/").append(maxAllays).append(")\n\n");

        if (allays.isEmpty()) {
            content.append("No allays bound yet.\nUse 'Bind Nearby Allay' to capture one!");
        }

        List<String> allayIds = new ArrayList<>();
        var builder = org.geysermc.cumulus.form.SimpleForm.builder()
                .title("Allay Vault")
                .content(content.toString());

        for (Map.Entry<String, AllayData> entry : allays.entrySet()) {
            String id = entry.getKey();
            AllayData data = entry.getValue();
            String status = data.active ? "[Summoned]" : "[Stored]";
            String typeTag = "[" + data.getType().displayName() + "]";
            builder.button(data.displayName() + " " + typeTag + " " + status);
            allayIds.add(id);
        }

        boolean canBind = allays.size() < maxAllays;
        if (canBind) builder.button("Bind Nearby Allay");
        builder.button("Summon All");
        builder.button("Store All");
        builder.button("Close");

        final int bindIndex = allayIds.size();
        final int summonAllIndex = canBind ? bindIndex + 1 : bindIndex;
        final int storeAllIndex = summonAllIndex + 1;

        builder.validResultHandler(response -> {
            int clicked = response.clickedButtonId();
            Bukkit.getScheduler().runTask(mgr.getPlugin(), () -> {
                if (!player.isOnline()) return;

                if (clicked < allayIds.size()) {
                    sendActionForm(player, mgr, allayIds.get(clicked));
                } else if (canBind && clicked == bindIndex) {
                    mgr.bind(player);
                } else if (clicked == summonAllIndex) {
                    mgr.summonAll(player);
                } else if (clicked == storeAllIndex) {
                    mgr.storeAll(player);
                }
            });
        });

        org.geysermc.floodgate.api.FloodgateApi.getInstance()
                .sendForm(player.getUniqueId(), builder.build());
    }

    // ==================== ACTION FORM ====================

    public static void sendActionForm(Player player, AllayManager mgr, String allayId) {
        try {
            doSendActionForm(player, mgr, allayId);
        } catch (Throwable t) {
            mgr.getPlugin().getLogger().warning(
                    "Floodgate action form failed, falling back to chest GUI: " + t.getMessage());
            dev.nafis.boundallay.gui.AllayVaultGUI.openManage(player, mgr, allayId);
        }
    }

    private static void doSendActionForm(Player player, AllayManager mgr, String allayId) {
        AllayData data = mgr.getStorage().get(player.getUniqueId(), allayId);
        if (data == null) {
            AllayManager.msg(player, "That allay no longer exists.", NamedTextColor.RED);
            return;
        }

        AllayType type = data.getType();
        String status = data.active ? "Summoned" : "Stored";
        String content =
                "Allay: " + data.displayName() + "\n" +
                "Type: " + type.displayName() + " (" + type.description() + ")\n" +
                "Status: " + status + "\n" +
                "Has bonded item: " + (data.heldItemB64 != null ? "Yes" : "No") + "\n" +
                "\nPick an action:";

        AllayType nextType = type.next();

        var form = org.geysermc.cumulus.form.SimpleForm.builder()
                .title("Manage: " + data.displayName())
                .content(content)
                .button("Summon")
                .button("Store")
                .button("Switch to " + nextType.displayName())
                .button("Release (Unbind)")
                .button("Back to List")
                .button("Close")
                .validResultHandler(response -> {
                    int id = response.clickedButtonId();
                    Bukkit.getScheduler().runTask(mgr.getPlugin(), () -> {
                        if (!player.isOnline()) return;
                        switch (id) {
                            case 0 -> mgr.summon(player, allayId);
                            case 1 -> mgr.store(player, allayId);
                            case 2 -> {
                                mgr.setType(player, allayId, nextType);
                                sendActionForm(player, mgr, allayId);
                            }
                            case 3 -> mgr.release(player, allayId);
                            case 4 -> sendListForm(player, mgr);
                            default -> {}
                        }
                    });
                })
                .build();

        org.geysermc.floodgate.api.FloodgateApi.getInstance()
                .sendForm(player.getUniqueId(), form);
    }
}

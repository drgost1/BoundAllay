package dev.nafis.boundallay;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

public final class AllayKeys {

    public static NamespacedKey BOUND_ENTITY;
    public static NamespacedKey OWNER_UUID;
    public static NamespacedKey ALLAY_NAME;
    public static NamespacedKey ALLAY_TYPE;

    private AllayKeys() {}

    public static void init(Plugin plugin) {
        BOUND_ENTITY = new NamespacedKey(plugin, "bound_entity");
        OWNER_UUID   = new NamespacedKey(plugin, "owner_uuid");
        ALLAY_NAME   = new NamespacedKey(plugin, "allay_name");
        ALLAY_TYPE   = new NamespacedKey(plugin, "allay_type");
    }
}

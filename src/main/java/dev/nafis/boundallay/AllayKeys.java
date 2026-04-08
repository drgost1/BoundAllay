package dev.nafis.boundallay;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Centralized PDC keys used on allay entities.
 */
public final class AllayKeys {

    /** Marks a live Allay entity as managed by BoundAllay. */
    public static NamespacedKey BOUND_ENTITY;

    /** Owner UUID string stored on entity PDC. */
    public static NamespacedKey OWNER_UUID;

    /** The allay's id/name key within the owner's collection. Stored on entity PDC. */
    public static NamespacedKey ALLAY_NAME;

    private AllayKeys() {}

    public static void init(Plugin plugin) {
        BOUND_ENTITY = new NamespacedKey(plugin, "bound_entity");
        OWNER_UUID   = new NamespacedKey(plugin, "owner_uuid");
        ALLAY_NAME   = new NamespacedKey(plugin, "allay_name");
    }
}

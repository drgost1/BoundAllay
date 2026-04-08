package dev.nafis.boundallay;

/**
 * Persistent record of one bound allay. In v3 each player can have multiple,
 * stored in a map keyed by the allay's id (name/key).
 *
 * Held / inv items are serialized via Paper's ItemStack#serializeAsBytes() then Base64.
 * Works identically for Java and Bedrock players (Floodgate UUIDs are stable).
 */
public class AllayData {

    /** The unique key for this allay within the player's collection. Also used as display name. */
    public String id;
    public String name;          // custom display name (nullable, falls back to id)
    public String heldItemB64;   // serialized bonded item (nullable)
    public String invItemB64;    // serialized 1-slot inventory item (nullable)
    public boolean active;       // currently summoned in world?
    public long lastSeen;        // epoch ms

    // Combat system fields
    public String type = "FIGHTER";       // AllayType name
    public boolean guardMode = false;     // guard mode active?
    public double guardX, guardY, guardZ; // guard location
    public String guardWorld;             // guard world name
    public boolean pvpMode = false;        // PvP attack mode

    public AllayData() {}

    /** v3 constructor with id. */
    public AllayData(String id, String name, String heldItemB64, String invItemB64) {
        this.id = id;
        this.name = name;
        this.heldItemB64 = heldItemB64;
        this.invItemB64 = invItemB64;
        this.active = false;
        this.lastSeen = System.currentTimeMillis();
    }

    /** v2 compatibility constructor (no id). Used during migration. */
    public AllayData(String name, String heldItemB64, String invItemB64) {
        this("default", name, heldItemB64, invItemB64);
    }

    /** Returns the display name, falling back to the id if name is null. */
    public String displayName() {
        return (name != null && !name.isEmpty()) ? name : id;
    }

    /** Get the AllayType enum for this data. */
    public AllayType getAllayType() {
        return AllayType.fromString(type);
    }
}

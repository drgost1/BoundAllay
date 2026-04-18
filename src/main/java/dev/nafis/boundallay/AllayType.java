package dev.nafis.boundallay;

import org.bukkit.Material;

public enum AllayType {
    FIGHTER("Fighter", "Attacks hostile mobs near the owner.", Material.IRON_SWORD),
    HEALER("Healer", "Periodically restores the owner's health.", Material.GOLDEN_APPLE),
    GUARDIAN("Guardian", "Defends the owner from any attacker (mobs + players).", Material.SHIELD);

    private final String displayName;
    private final String description;
    private final Material icon;

    AllayType(String displayName, String description, Material icon) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
    public Material icon() { return icon; }

    public AllayType next() {
        AllayType[] v = values();
        return v[(this.ordinal() + 1) % v.length];
    }

    public static AllayType parse(String s, AllayType fallback) {
        if (s == null) return fallback;
        try { return valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return fallback; }
    }
}

package dev.nafis.boundallay;

/**
 * Types of bound allays, each with unique combat/utility behavior.
 */
public enum AllayType {
    FIGHTER("Fighter", "Attacks hostile mobs near owner"),
    HEALER("Healer", "Gives regeneration to owner"),
    GUARDIAN("Guardian", "Guards a location, attacks hostiles"),
    COLLECTOR("Collector", "Auto-picks up nearby drops for owner");

    private final String displayName;
    private final String description;

    AllayType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    /** Get the next type in the cycle. */
    public AllayType next() {
        AllayType[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** Safe parse from string, defaults to FIGHTER. */
    public static AllayType fromString(String s) {
        if (s == null) return FIGHTER;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return FIGHTER;
        }
    }
}

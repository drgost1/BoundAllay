package dev.nafis.boundallay;

public class AllayData {

    public String id;
    public String name;
    public String heldItemB64;
    public String invItemB64;
    public boolean active;
    public long lastSeen;
    public String type;

    public AllayData() {}

    public AllayData(String id, String name, String heldItemB64, String invItemB64, AllayType type) {
        this.id = id;
        this.name = name;
        this.heldItemB64 = heldItemB64;
        this.invItemB64 = invItemB64;
        this.active = false;
        this.lastSeen = System.currentTimeMillis();
        this.type = type.name();
    }

    public String displayName() {
        return (name != null && !name.isEmpty()) ? name : id;
    }

    public AllayType getType() {
        return AllayType.parse(type, AllayType.FIGHTER);
    }

    public void setType(AllayType t) {
        this.type = t.name();
    }
}

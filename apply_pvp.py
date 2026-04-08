import re
import os

BASE = "/tmp/BoundAllay/src/main/java/dev/nafis/boundallay"

def read(path):
    with open(path, "r") as f:
        return f.read()

def write(path, content):
    with open(path, "w") as f:
        f.write(content)

# ============================================================
# 1. AllayData.java - add pvpMode field
# ============================================================
path = f"{BASE}/AllayData.java"
src = read(path)
src = src.replace(
    "public String guardWorld;             // guard world name",
    "public String guardWorld;             // guard world name\n    public boolean pvpMode = false;        // PvP attack mode"
)
write(path, src)
print("AllayData.java: pvpMode field added")

# ============================================================
# 2. AllayManager.java - add PvP tracking + togglePvp + modified tickFighter + optimizations
# ============================================================
path = f"{BASE}/AllayManager.java"
src = read(path)

# 2a. Add lastAttacker map + timestamp map after inCombat field
src = src.replace(
    "    private final Set<UUID> inCombat = ConcurrentHashMap.newKeySet();",
    "    private final Set<UUID> inCombat = ConcurrentHashMap.newKeySet();\n"
    "\n"
    "    /** PvP: ownerUUID -> attackerPlayerUUID */\n"
    "    private final Map<UUID, UUID> lastAttacker = new ConcurrentHashMap<>();\n"
    "    /** PvP: ownerUUID -> timestamp of last attack */\n"
    "    private final Map<UUID, Long> lastAttackTime = new ConcurrentHashMap<>();"
)

# 2b. Add togglePvp method after toggleGuard method
toggle_guard_end = "        storage.set(owner, allayName, data);\n    }\n\n    // ==================== HELPERS ===================="
pvp_section = """        storage.set(owner, allayName, data);
    }

    // ==================== PVP ====================

    /**
     * Toggle PvP mode for a specific allay.
     */
    public void togglePvp(Player player, String allayName) {
        UUID owner = player.getUniqueId();
        AllayData data = storage.get(owner, allayName);
        if (data == null) {
            msg(player, "No allay named '" + allayName + "'.", NamedTextColor.RED);
            return;
        }
        data.pvpMode = !data.pvpMode;
        storage.set(owner, allayName, data);
        msg(player, "'" + allayName + "' PvP mode: " + (data.pvpMode ? "ON" : "OFF"),
                data.pvpMode ? NamedTextColor.GREEN : NamedTextColor.GOLD);
    }

    /**
     * Record that a player attacked the owner. Called from AllayListener.
     */
    public void recordAttacker(UUID ownerUUID, UUID attackerUUID) {
        lastAttacker.put(ownerUUID, attackerUUID);
        lastAttackTime.put(ownerUUID, System.currentTimeMillis());
    }

    /** Get lastAttacker map (for listener access). */
    public Map<UUID, UUID> getLastAttackerMap() { return lastAttacker; }
    /** Get lastAttackTime map (for listener access). */
    public Map<UUID, Long> getLastAttackTimeMap() { return lastAttackTime; }

    /** Get the active allays map (for allay-vs-allay lookup). */
    public Map<UUID, Map<String, UUID>> getActiveAllaysMap() { return activeAllays; }

    // ==================== HELPERS ===================="""
src = src.replace(toggle_guard_end, pvp_section)

# 2c. Replace tickFighter to include PvP player targeting + allay-vs-allay
old_tickFighter = """    private void tickFighter(Allay allay, Player owner) {
        Monster target = findHostileTargeting(owner, config.fighterRange);
        if (target == null) { inCombat.remove(allay.getUniqueId()); return; }
        inCombat.add(allay.getUniqueId());

        Location mobLoc = target.getLocation();
        allay.teleport(mobLoc.clone().add(0, 0.5, 0));
        target.damage(config.fighterDamage, allay);

        World world = mobLoc.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.CRIT, mobLoc.clone().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.1);
        }
    }"""

new_tickFighter = """    private void tickFighter(Allay allay, Player owner) {
        UUID ownerId = owner.getUniqueId();
        AllayData fData = storage.get(ownerId, readAllayName(allay));

        // 1. PvP player targeting: if pvpMode is on and owner was recently attacked
        if (fData != null && fData.pvpMode) {
            UUID attackerUUID = lastAttacker.get(ownerId);
            Long attackTime = lastAttackTime.get(ownerId);
            if (attackerUUID != null && attackTime != null
                    && (System.currentTimeMillis() - attackTime) < 10_000L) {
                Player attacker = Bukkit.getPlayer(attackerUUID);
                if (attacker != null && attacker.isOnline() && !attacker.isDead()
                        && attacker.getWorld().equals(owner.getWorld())
                        && attacker.getLocation().distanceSquared(owner.getLocation())
                           < config.fighterRange * config.fighterRange * 4) {
                    inCombat.add(allay.getUniqueId());
                    Location pLoc = attacker.getLocation();
                    allay.teleport(pLoc.clone().add(0, 0.5, 0));
                    attacker.damage(config.fighterDamage, allay);
                    World world = pLoc.getWorld();
                    if (world != null) {
                        world.spawnParticle(Particle.CRIT, pLoc.clone().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.1);
                    }

                    // 2. Allay vs Allay: attack enemy allays nearby
                    for (Map.Entry<UUID, Map<String, UUID>> enemyOwnerEntry : activeAllays.entrySet()) {
                        if (enemyOwnerEntry.getKey().equals(ownerId)) continue;
                        if (!enemyOwnerEntry.getKey().equals(attackerUUID)) continue;
                        for (UUID enemyEntityId : enemyOwnerEntry.getValue().values()) {
                            Entity enemyEnt = Bukkit.getEntity(enemyEntityId);
                            if (enemyEnt instanceof Allay enemyAllay && !enemyAllay.isDead()
                                    && enemyAllay.getWorld().equals(allay.getWorld())
                                    && enemyAllay.getLocation().distanceSquared(allay.getLocation()) < config.fighterRange * config.fighterRange) {
                                String enemyAllayId = readAllayName(enemyAllay);
                                UUID enemyOwnerId = readOwner(enemyAllay);
                                if (enemyOwnerId != null && enemyAllayId != null) {
                                    AllayData enemyData = storage.get(enemyOwnerId, enemyAllayId);
                                    if (enemyData != null && enemyData.pvpMode) {
                                        enemyAllay.damage(config.fighterDamage, allay);
                                        World ew = enemyAllay.getLocation().getWorld();
                                        if (ew != null) {
                                            ew.spawnParticle(Particle.CRIT, enemyAllay.getLocation().add(0, 0.5, 0), 3, 0.2, 0.2, 0.2, 0.05);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return; // handled PvP target this tick
                }
            }
            // Clear stale attacker
            if (attackTime != null && (System.currentTimeMillis() - attackTime) >= 10_000L) {
                lastAttacker.remove(ownerId);
                lastAttackTime.remove(ownerId);
            }
        }

        // 3. Normal mob targeting (limit 1 target per tick)
        Monster target = findHostileTargeting(owner, config.fighterRange);
        if (target == null) { inCombat.remove(allay.getUniqueId()); return; }
        inCombat.add(allay.getUniqueId());

        Location mobLoc = target.getLocation();
        allay.teleport(mobLoc.clone().add(0, 0.5, 0));
        target.damage(config.fighterDamage, allay);

        World world = mobLoc.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.CRIT, mobLoc.clone().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.1);
        }
    }"""

src = src.replace(old_tickFighter, new_tickFighter)

# 2d. Replace tickCollector with optimized version (limit 3 items)
old_tickCollector = """    private void tickCollector(Allay allay, Player owner) {
        int range = config.collectorRange;

        // Find nearest item
        Item nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : allay.getNearbyEntities(range, range, range)) {
            if (!(e instanceof Item item)) continue;
            if (item.isDead() || item.getItemStack().getType().isAir()) continue;
            if (item.getTicksLived() < 40) continue;
            double dist = item.getLocation().distanceSquared(allay.getLocation());
            if (dist < nearestDist) {
                nearest = item;
                nearestDist = dist;
            }
        }
        if (nearest == null) return;

        // Move allay toward the item
        Location itemLoc = nearest.getLocation();
        double distToItem = allay.getLocation().distance(itemLoc);

        if (distToItem > 1.5) {
            // Teleport allay closer to item (step by step)
            Vector dir = itemLoc.toVector().subtract(allay.getLocation().toVector()).normalize().multiply(1.0);
            allay.teleport(allay.getLocation().add(dir));
            inCombat.add(allay.getUniqueId()); // prevent follow teleport while collecting
            return;
        }

        // Allay is close enough \xe2\x80\x94 pick up the item
        inCombat.remove(allay.getUniqueId());
        HashMap<Integer, ItemStack> leftover = owner.getInventory().addItem(nearest.getItemStack());
        if (leftover.isEmpty()) {
            nearest.remove();
            World world = itemLoc.getWorld();
            if (world != null) {
                world.spawnParticle(Particle.HAPPY_VILLAGER, itemLoc, 8, 0.3, 0.5, 0.3, 0.0);
            }
        }
    }"""

new_tickCollector = """    private void tickCollector(Allay allay, Player owner) {
        int range = config.collectorRange;

        // Find nearest items (limit scan to 3 candidates for performance)
        List<Item> nearbyItems = new ArrayList<>();
        for (Entity e : allay.getNearbyEntities(range, range, range)) {
            if (!(e instanceof Item item)) continue;
            if (item.isDead() || item.getItemStack().getType().isAir()) continue;
            if (item.getTicksLived() < 40) continue;
            nearbyItems.add(item);
            if (nearbyItems.size() >= 3) break;
        }
        if (nearbyItems.isEmpty()) { inCombat.remove(allay.getUniqueId()); return; }

        // Sort by distance, pick the closest
        nearbyItems.sort(Comparator.comparingDouble(
                i -> i.getLocation().distanceSquared(allay.getLocation())));
        Item nearest = nearbyItems.get(0);

        // Move allay toward the item
        Location itemLoc = nearest.getLocation();
        double distToItem = allay.getLocation().distance(itemLoc);

        if (distToItem > 1.5) {
            Vector dir = itemLoc.toVector().subtract(allay.getLocation().toVector()).normalize().multiply(1.0);
            allay.teleport(allay.getLocation().add(dir));
            inCombat.add(allay.getUniqueId());
            return;
        }

        // Allay is close enough - pick up the item
        inCombat.remove(allay.getUniqueId());
        HashMap<Integer, ItemStack> leftover = owner.getInventory().addItem(nearest.getItemStack());
        if (leftover.isEmpty()) {
            nearest.remove();
            World world = itemLoc.getWorld();
            if (world != null) {
                world.spawnParticle(Particle.HAPPY_VILLAGER, itemLoc, 8, 0.3, 0.5, 0.3, 0.0);
            }
        }
    }"""

src = src.replace(old_tickCollector, new_tickCollector)

# 2e. Optimization: tickFollow - skip if no players online
old_tickFollow = "    public void tickFollow() {\n        for (Map.Entry<UUID, Map<String, UUID>> ownerEntry : new HashMap<>(activeAllays).entrySet()) {"
new_tickFollow = "    public void tickFollow() {\n        if (Bukkit.getOnlinePlayers().isEmpty()) return;\n        for (Map.Entry<UUID, Map<String, UUID>> ownerEntry : new HashMap<>(activeAllays).entrySet()) {"
src = src.replace(old_tickFollow, new_tickFollow)

# 2f. Optimization: tickCombat - skip if no players online
old_tickCombat = "    public void tickCombat() {\n        if (Bukkit.getOnlinePlayers().isEmpty()) return;\n"
# Check if already applied... no, the original doesn't have it yet
old_tickCombat2 = "    public void tickCombat() {\n        for (Map.Entry<UUID, Map<String, UUID>> ownerEntry : new HashMap<>(activeAllays).entrySet()) {"
new_tickCombat2 = "    public void tickCombat() {\n        if (Bukkit.getOnlinePlayers().isEmpty()) return;\n        for (Map.Entry<UUID, Map<String, UUID>> ownerEntry : new HashMap<>(activeAllays).entrySet()) {"
src = src.replace(old_tickCombat2, new_tickCombat2)

# Add Comparator import if not present
if "import java.util.Comparator;" not in src:
    # java.util.* covers Comparator, so no need
    pass

write(path, src)
print("AllayManager.java: PvP system + optimizations added")

# ============================================================
# 3. AllayVaultGUI.java - add PvP button to manage screen
# ============================================================
path = f"{BASE}/gui/AllayVaultGUI.java"
src = read(path)

# Add MANAGE_PVP_SLOT constant
src = src.replace(
    "    public static final int MANAGE_GUARD_SLOT = 24;",
    "    public static final int MANAGE_GUARD_SLOT = 24;\n    public static final int MANAGE_PVP_SLOT = 8;"
)

# Add PvP button in openManage, right before the back button section
pvp_button_code = '''
        // PvP button
        inv.setItem(MANAGE_PVP_SLOT, labeled(
                Material.IRON_SWORD,
                "PvP: " + (data.pvpMode ? "ON" : "OFF"),
                data.pvpMode ? NamedTextColor.GREEN : NamedTextColor.RED,
                List.of(data.pvpMode ? "Allay attacks players who hit you" : "Allay ignores player attackers", "", "Click to toggle")
        ));

        // Back button'''
src = src.replace("        // Back button", pvp_button_code)

write(path, src)
print("AllayVaultGUI.java: PvP button added")

# ============================================================
# 4. VaultListener.java - handle PvP slot click
# ============================================================
path = f"{BASE}/listeners/VaultListener.java"
src = read(path)

src = src.replace(
    """            case AllayVaultGUI.MANAGE_GUARD_SLOT -> {
                player.closeInventory();
                mgr.toggleGuard(player, allayId);
                org.bukkit.Bukkit.getScheduler().runTask(mgr.getPlugin(), () -> {
                    if (player.isOnline()) {
                        AllayVaultGUI.openManage(player, mgr, allayId);
                    }
                });
            }""",
    """            case AllayVaultGUI.MANAGE_GUARD_SLOT -> {
                player.closeInventory();
                mgr.toggleGuard(player, allayId);
                org.bukkit.Bukkit.getScheduler().runTask(mgr.getPlugin(), () -> {
                    if (player.isOnline()) {
                        AllayVaultGUI.openManage(player, mgr, allayId);
                    }
                });
            }
            case AllayVaultGUI.MANAGE_PVP_SLOT -> {
                player.closeInventory();
                mgr.togglePvp(player, allayId);
                org.bukkit.Bukkit.getScheduler().runTask(mgr.getPlugin(), () -> {
                    if (player.isOnline()) {
                        AllayVaultGUI.openManage(player, mgr, allayId);
                    }
                });
            }"""
)

write(path, src)
print("VaultListener.java: PvP slot handler added")

# ============================================================
# 5. AllayListener.java - track player-on-player damage for PvP allays
# ============================================================
path = f"{BASE}/listeners/AllayListener.java"
src = read(path)

pvp_damage_listener = """
    /** Track when a player attacks the allay's owner (for PvP allay targeting). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onOwnerDamagedByPlayer(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Player victim)) return;
        // Resolve actual damager (could be projectile)
        org.bukkit.entity.Entity rawDamager = e.getDamager();
        org.bukkit.entity.Player attacker = null;
        if (rawDamager instanceof org.bukkit.entity.Player p) {
            attacker = p;
        } else if (rawDamager instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof org.bukkit.entity.Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;

        // Record attacker so PvP allays can target them
        mgr.recordAttacker(victim.getUniqueId(), attacker.getUniqueId());
    }

"""

src = src.replace(
    "    public void onQuit(PlayerQuitEvent e) {",
    pvp_damage_listener + "    public void onQuit(PlayerQuitEvent e) {"
)

write(path, src)
print("AllayListener.java: PvP damage tracking listener added")

# ============================================================
# 6. BedrockMenu.java - add Toggle PvP button
# ============================================================
path = f"{BASE}/BedrockMenu.java"
src = read(path)

# Add PvP info to content
src = src.replace(
    '"\\nGuard Mode: " + (data.guardMode ? "ON" : "OFF")',
    '"\\nGuard Mode: " + (data.guardMode ? "ON" : "OFF") +\n                "\\nPvP Mode: " + (data.pvpMode ? "ON" : "OFF")'
)

# Add Toggle PvP button after Toggle Guard Mode button
src = src.replace(
    '                .button("Toggle Guard Mode")',
    '                .button("Toggle Guard Mode")\n                .button("Toggle PvP")'
)

# Update the button index handling
src = src.replace(
    """                        switch (id) {
                            case 0 -> mgr.summon(player, allayId);
                            case 1 -> mgr.store(player, allayId);
                            case 2 -> { mgr.cycleType(player, allayId); sendActionForm(player, mgr, allayId); }
                            case 3 -> { mgr.toggleGuard(player, allayId); sendActionForm(player, mgr, allayId); }
                            case 4 -> mgr.release(player, allayId);
                            case 5 -> sendListForm(player, mgr);
                            default -> { /* close */ }
                        }""",
    """                        switch (id) {
                            case 0 -> mgr.summon(player, allayId);
                            case 1 -> mgr.store(player, allayId);
                            case 2 -> { mgr.cycleType(player, allayId); sendActionForm(player, mgr, allayId); }
                            case 3 -> { mgr.toggleGuard(player, allayId); sendActionForm(player, mgr, allayId); }
                            case 4 -> { mgr.togglePvp(player, allayId); sendActionForm(player, mgr, allayId); }
                            case 5 -> mgr.release(player, allayId);
                            case 6 -> sendListForm(player, mgr);
                            default -> { /* close */ }
                        }"""
)

write(path, src)
print("BedrockMenu.java: Toggle PvP button added")

# ============================================================
# 7. AllayCommand.java - add pvp subcommand
# ============================================================
path = f"{BASE}/commands/AllayCommand.java"
src = read(path)

# Add "pvp" to USER_SUBS list
src = src.replace(
    'List.of("bind", "summon", "store", "release", "rename", "list", "summonall", "storeall", "info", "type", "guard", "help")',
    'List.of("bind", "summon", "store", "release", "rename", "list", "summonall", "storeall", "info", "type", "guard", "pvp", "help")'
)

# Add pvp case in the switch
src = src.replace(
    '''            case "guard" -> {
                if (args.length < 2) {
                    send(p, "Usage: /allay guard <name>", NamedTextColor.RED);
                } else {
                    mgr.toggleGuard(p, joinArgs(args, 1));
                }
            }
            case "help" -> help(p);''',
    '''            case "guard" -> {
                if (args.length < 2) {
                    send(p, "Usage: /allay guard <name>", NamedTextColor.RED);
                } else {
                    mgr.toggleGuard(p, joinArgs(args, 1));
                }
            }
            case "pvp" -> {
                if (args.length < 2) {
                    send(p, "Usage: /allay pvp <name>", NamedTextColor.RED);
                } else {
                    mgr.togglePvp(p, joinArgs(args, 1));
                }
            }
            case "help" -> help(p);'''
)

# Add tab completion for pvp subcommand
src = src.replace(
    '            if (sub.equals("summon") || sub.equals("store") || sub.equals("release")\n                    || sub.equals("rename") || sub.equals("info")) {',
    '            if (sub.equals("summon") || sub.equals("store") || sub.equals("release")\n                    || sub.equals("rename") || sub.equals("info") || sub.equals("pvp")) {'
)

# Add pvp to help text
src = src.replace(
    '        send(p, "/allay guard <name> - toggle guard mode (Guardian type)", NamedTextColor.GRAY);',
    '        send(p, "/allay guard <name> - toggle guard mode (Guardian type)", NamedTextColor.GRAY);\n        send(p, "/allay pvp <name>   - toggle PvP attack mode", NamedTextColor.GRAY);'
)

write(path, src)
print("AllayCommand.java: pvp subcommand added")

print("\n=== All edits applied successfully ===")

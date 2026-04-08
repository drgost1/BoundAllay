# BoundAllay v2.1

Paper plugin (Minecraft 1.21+) that makes Allays into permanent companions that **never get lost**. Fully GUI-based, **cross-play compatible** (Java + Bedrock via Geyser/Floodgate), fully configurable.

## What's new in v2.1

- **`config.yml`** — all tunables exposed, no recompilation needed
- **`/allay reload`** — reload config at runtime (admin-only, `boundallay.admin` perm)
- **Invincibility toggle** — optionally make bound allays take zero damage (all sources)
- **PvP damage toggle** — separately configurable (default on)
- **Safe value clamping** — misconfigured values auto-clamp to safe ranges with a warning in logs instead of crashing

## What's new in v2.0

- **No inventory item.** Everything is managed through a menu — no more lost items, no more inventory-full edge cases.
- **Cross-play native.** Works on Java + Bedrock the same way. Bedrock players get native Floodgate forms (if Floodgate installed) or a chest GUI (if not).
- **Command-based binding.** `/allay bind` replaces sneak+right-click, which doesn't work well on Bedrock touch controls.
- **Anti-spam cooldown.** Cooldown on summon/store/bind actions (auto-respawn bypasses it).

## What it solves

| Vanilla / cross-play problem | BoundAllay fix |
|---|---|
| Allay wanders off / despawns | Persistent + auto-follow via tick task |
| Allay dies → lost forever | Auto-respawn at owner 3s after death |
| Allay left in Nether portal | Cross-dimension teleport on world change |
| Allay lost on logout | Stored to persistent JSON on quit |
| Server crash mid-save | Atomic file writes (`.tmp` → `ATOMIC_MOVE`) |
| Other players grief the allay | Player-dealt damage blocked |
| Bedrock players can't sneak+click entities | Command + GUI button for binding |
| Bedrock chest GUIs are clunky on mobile | Native Floodgate forms (optional) |

## Build

```bash
mvn clean package
```

Output: `target/BoundAllay-2.0.0.jar` → drop into `plugins/`, restart.

Requires Java 21. Floodgate is an **optional** dependency — plugin loads fine without it.

## How to use

**Bind a wild allay:**
- Find a wild allay (Pillager Outpost / Woodland Mansion cages)
- Stand within 5 blocks of it
- Run `/allay bind` OR click "Bind Nearby Allay" in the `/allay` menu

**Summon / store / status:**
- `/allay` opens the menu (chest GUI on Java, native form on Bedrock if Floodgate present)
- Or use direct commands: `/allay summon`, `/allay store`, `/allay info`

## Cross-play architecture

```
Player runs /allay
        │
        ▼
AllayManager.openVault(player)
        │
        ├── BedrockMenu.shouldUseForm(uuid)?
        │       │
        │       ├── Yes → Floodgate SimpleForm (native Bedrock UI)
        │       │         Callback hops to main thread via Bukkit scheduler
        │       │
        │       └── No  → AllayVaultGUI (27-slot chest, works on both Java + Bedrock via Geyser)
        │                 VaultListener handles clicks
        │
        ▼
   AllayManager.summon / store / bind
        │
        ▼
   AllayStorage (atomic JSON writes)
```

### Why both paths?

- **Floodgate forms** are the right UX for Bedrock — big touch-friendly buttons, native look, no hover tooltips needed. But Floodgate is a hard dep most cross-play servers have anyway.
- **Chest GUI** is the fallback — works for Java and Bedrock (through Geyser) without Floodgate. It's clunkier on mobile but functional.
- **All Floodgate code is isolated in `BedrockMenu.java`** and wrapped in try/catch, so the plugin loads even without Floodgate. The JVM only links Floodgate classes when the form code path is actually hit.

### Threading note for Bedrock

Floodgate form callbacks run on the Netty/Geyser thread, **not** the Bukkit main thread. Any Bukkit API call from the callback MUST hop back via `Bukkit.getScheduler().runTask(plugin, ...)`. This is done in `BedrockMenu.doSendForm`. Forgetting this would cause random async-access crashes that only trigger for Bedrock players — very hard to debug after the fact.

## Architecture

```
BoundAllayPlugin       - bootstrap, scheduler, Floodgate detection
AllayKeys              - NamespacedKey constants (entity PDC only)
AllayData              - POJO for JSON
AllayStorage           - atomic JSON read/write
AllayManager           - bind/summon/store/respawn/follow, cooldowns
BedrockMenu            - Floodgate bridge (optional, graceful degrade)
gui/AllayVaultGUI      - chest GUI builder (Bedrock-friendly)
gui/AllayVaultHolder   - custom InventoryHolder for safe click identification
listeners/AllayListener- death, damage, teleport, world change, quit, join
listeners/VaultListener- chest GUI click handler
commands/AllayCommand  - /allay with tab completion
```

## Configuration (`plugins/BoundAllay/config.yml`)

All values are validated on load. Out-of-range values get clamped to safe limits with a warning in `latest.log`.

```yaml
follow:
  distance: 16.0        # blocks before teleport kicks in (4.0 - 64.0)
  tick-interval: 10     # ticks between follow checks (5 - 100, 20 = 1s)

bind:
  radius: 5.0           # max distance for /allay bind (1.0 - 16.0)

death:
  respawn-delay-ticks: 60   # delay before auto-respawn (0 - 600)
  invincible: false         # take zero damage from ANY source
  block-pvp-damage: true    # cancel player-dealt damage

cooldown:
  action-ms: 2000       # ms between summon/store/bind actions (0 - 60000)

bedrock:
  prefer-native-forms: true # use Floodgate forms for Bedrock players
```

**Reload at runtime:** `/allay reload` (requires `boundallay.admin` permission or OP). Console works too.

**Caveat:** `follow.tick-interval` requires a full restart to take effect — the scheduler is already running with the old value. Everything else applies immediately on reload.

**Two useful presets:**

| Use case | Settings |
|---|---|
| Creative / build server | `invincible: true`, `block-pvp-damage: true` (allays literally cannot die) |
| PvP arena server | `invincible: false`, `block-pvp-damage: false` (allays are collateral, auto-respawn still saves them) |

## Testing checklist (cross-play)

**Java side:**
- [ ] `/allay bind` near a wild allay → message confirms bind
- [ ] `/allay` → chest GUI opens with Summon / Store / Bind / Close
- [ ] Click Summon → allay spawns
- [ ] Walk 50 blocks → allay teleports to you
- [ ] Enter Nether → allay follows
- [ ] Die to lava → respawns in 3s
- [ ] Logout while summoned → login → `/allay info` shows Stored

**Bedrock side (via Geyser):**
- [ ] `/allay bind` works identically
- [ ] `/allay` with Floodgate installed → native form with 5 buttons
- [ ] `/allay` without Floodgate → chest GUI renders correctly on touch
- [ ] Tap Summon in form → allay spawns on main thread (no async crash)
- [ ] All death/follow/persistence tests above work identically

**Shared:**
- [ ] Java player's allay is invisible to Bedrock damage, and vice versa
- [ ] Server restart with 5 active allays (mixed Java/Bedrock owners) → all preserved

## Known limits

- **One allay per player.** Storage is `Map<UUID, AllayData>`. Multi-allay would need list refactor.
- **Allay duplication (amethyst + note block):** dupes are wild, not auto-bound.
- **Cross-server/Velocity networks:** JSON is per-server. Swap `AllayStorage` for Redis/MySQL if you need network-wide state.
- **Floodgate version:** coded against Floodgate 2.2.x Cumulus form API. If you're on older Floodgate, the form path will fail and fall back to chest GUI (still works).

## Gotchas I hit while building this

- **`createBoundItem` doesn't exist anymore.** v1 used items; v2 is GUI-only. If you see references in stale code, delete them.
- **`data.inVault` was removed.** Only `active` matters now.
- **Main thread safety for Floodgate callbacks** — see threading note above.
- **Bedrock Unicode rendering** — avoid fancy chars like ✦ in GUI names. Stick to plain ASCII.

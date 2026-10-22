# Excavate 26.3 — v0.2 (experimental)

Client-only Fabric excavation controller for **Minecraft Java 26.3**, using the **official Baritone v1.20.0 Fabric mod** for pathfinding. Designed for a private server that permits automation.

**GitHub Actions builds a JAR automatically on pushes to main.** Download it from a successful run's **Artifacts** section. Use the latest v0.2 build, not the old v0.1 file.

## Version 0.2 features

- Two-corner selection and Baritone area clearing
- Automatic inventory monitoring (when three or fewer slots are free)
- Multiple, explicitly registered **placed chests** and **placed shulker boxes** as unloading destinations
- Attempts storage containers in registration order; falls through to the next when one is inaccessible, full, or cannot accept eligible stacks
- Standard client-side container `QUICK_MOVE` (shift-click) interactions (no server-side mod)
- Deposits eligible main-inventory block stacks and common ore drops; protects the nine hotbar slots, damageable tools/equipment, shulker box items, chest items, and non-allowlisted items
- Resumes Baritone excavation only if at least **six** inventory slots are empty
- Pauses instead of dropping items when all storage options fail, and clears coordinates on world/dimension change

## Commands

| Command | Description |
| --- | --- |
| `/excavate pos1` | Mark first excavation corner at player feet |
| `/excavate pos2` | Mark second corner at player feet |
| `/excavate storage add` | **Look at a placed chest or shulker box**, then register the targeted block |
| `/excavate chest` | Alias for `/excavate storage add` (new behavior!) |
| `/excavate storage remove` | Look at a registered storage block and remove it |
| `/excavate storage list` | Show registered destinations in their selection order |
| `/excavate storage clear` | Remove all registered destinations |
| `/excavate start` | Start clearing the selected rectangular volume |
| `/excavate unload` | Manually test an unloading run with eligible inventory items |
| `/excavate status` | Display controller state, slots, number of destinations, and stack transfers |
| `/excavate pause` | Interrupt mining/storage and pause |
| `/excavate resume` | Restart excavation after a pause; also checks inventory space |
| `/excavate stop` | Stop operations, retaining configuration for this world session |

## Testing v0.2

1. Back up the world and test in a **small disposable area**. Install Fabric, Fabric API, Baritone standalone, and the compiled Excavate JAR into your client mods folder.
2. Place one empty normal chest and one empty shulker box in reachable, **unobstructed** locations. Look directly at each and run `/excavate storage add`, then `/excavate storage list`. A double chest is supported as one menu.
3. Place several ordinary cobblestone stacks in your **main inventory** (slots 9–35), not the hotbar. Run `/excavate unload` and watch for navigation, GUI opening, item transfers, and closing. Confirm the stacks arrived at the correct container.
4. With a few eligible material stacks and a damaged pickaxe in the main inventory, verify the pickaxe stays with you. Similarly confirm hotbar items remain untouched.
5. Fill the first chest and verify the mod tries the registered shulker as a fallback. It should never drop items if both destinations are full.
6. Define `/excavate pos1` and `/excavate pos2`, then run `/excavate start` with spare inventory slots. Allow mining to fill your inventory until the automatic unloading threshold triggers.
7. Verify automatic excavation resumes only after at least six inventory slots become free.

## Safety and limitations

- **Experimental: compilation success does not mean tested in Minecraft.** Verify transfer contents manually in a test world before use with valuable supplies.
- Registrations are **session-only** and cleared when changing worlds or dimensions; the mod does not save settings to disk yet.
- Only **placed** chests/shulker boxes are targets. It does not place shulker boxes from inventory, put items into shulkers carried as items, or support ender chests/barrels.
- In the current build, protected items can prevent the inventory from reaching six empty slots. In that case the mod pauses instead of discarding protected contents.
- The initial safe default deposit list is limited to non-chest/non-shulker block items and common mining drops: coal, charcoal, raw iron/copper/gold, diamond, emerald, redstone, lapis, quartz, amethyst shards, flint, clay balls, glowstone dust, netherite scraps.
- The mod uses **ordinary client GUI transactions**. A shift-click updates the client immediately; server confirmation may be implicit. It checks slot progression after a delay and falls back on failure, but additional multiplayer testing is needed to validate edge cases (lag, protections, plugin-managed inventories, obstruction and reconnection).
- Placed containers must be openable by the player according to normal server rules. An inaccessible/blocked container may time out and trigger fallback.
- Mining completion detection, persistent profiles, tool durability policies and a configurable item whitelist are planned for later releases.

## Development and CI

See `.github/workflows/build.yml`. The pipeline uses Java 25, Gradle 9.7.1, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3 and checksum-verified Baritone API 1.20.0.

The source project depends on the official Baritone standalone Fabric mod **at runtime**; the small `baritone-api-1.20.0.jar` CI artifact is used **only for compilation**.

- Fabric: https://fabricmc.net/develop/
- Baritone: https://github.com/cabaletta/baritone/releases/tag/v1.20.0

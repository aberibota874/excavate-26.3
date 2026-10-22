package dev.example.excavate;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft 26.3 client-side excavation controller.
 * All container transfers use the ordinary client menu input API.
 * v0.2 keeps registrations in memory only; crossing worlds clears them.
 */
public final class ExcavateClient implements ClientModInitializer {
    private enum Mode { IDLE, MINING, GOING_TO_STORAGE, OPENING, TRANSFERRING, SETTLING, PAUSED }
    private enum Kind { CHEST, SHULKER }
    private record Target(BlockPos pos, Kind kind) { }

    private static final int RESERVE_SLOTS = 3;
    private static final int RESUME_SLOTS = 6;
    private static final int PATH_TIMEOUT_TICKS = 3600;
    private static final int OPEN_TIMEOUT_TICKS = 120;
    private static final int CLICK_DELAY_TICKS = 8;

    // Conservative explicit mined-resource allowlist. Everything else is retained.
    private static final Set<Item> ORE_DROPS = Set.of(
        Items.COAL, Items.CHARCOAL, Items.RAW_IRON, Items.RAW_COPPER,
        Items.RAW_GOLD, Items.DIAMOND, Items.EMERALD, Items.REDSTONE,
        Items.LAPIS_LAZULI, Items.QUARTZ, Items.AMETHYST_SHARD,
        Items.FLINT, Items.CLAY_BALL, Items.GLOWSTONE_DUST,
        Items.NETHERITE_SCRAP
    );

    private final List<Target> destinations = new ArrayList<>();
    private BlockPos corner1, corner2;
    private ClientLevel attachedLevel;
    private Mode mode = Mode.IDLE;
    private int activeDestination = -1;
    private int stateTicks = 0;
    private int heartbeat = 0;
    private int pendingSlot = -1;
    private int pendingCount = 0;
    private ItemStack pendingItem = ItemStack.EMPTY;
    private int transferCount = 0;
    private boolean movedThisChest = false;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            dispatcher.register(ClientCommands.literal("excavate")
                .then(ClientCommands.literal("pos1").executes(ctx -> { mark(true); return 1; }))
                .then(ClientCommands.literal("pos2").executes(ctx -> { mark(false); return 1; }))
                .then(ClientCommands.literal("chest").executes(ctx -> { registerStorage(); return 1; }))
                .then(ClientCommands.literal("storage")
                    .then(ClientCommands.literal("add").executes(ctx -> { registerStorage(); return 1; }))
                    .then(ClientCommands.literal("remove").executes(ctx -> { removeStorage(); return 1; }))
                    .then(ClientCommands.literal("list").executes(ctx -> { listStorage(); return 1; }))
                    .then(ClientCommands.literal("clear").executes(ctx -> { clearStorage(); return 1; })))
                .then(ClientCommands.literal("start").executes(ctx -> { begin(); return 1; }))
                .then(ClientCommands.literal("unload").executes(ctx -> { beginUnloading(); return 1; }))
                .then(ClientCommands.literal("resume").executes(ctx -> { resume(); return 1; }))
                .then(ClientCommands.literal("pause").executes(ctx -> { pause(); return 1; }))
                .then(ClientCommands.literal("stop").executes(ctx -> { stop(); return 1; }))
                .then(ClientCommands.literal("status").executes(ctx -> { status(); return 1; }))
            );
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    private void mark(boolean first) {
        if (busy()) { tell("Pause or stop before changing excavation corners."); return; }
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        if (first) corner1 = p.blockPosition().immutable();
        else corner2 = p.blockPosition().immutable();
        tell((first ? "First" : "Second") + " corner: " + p.blockPosition());
    }

    private boolean busy() { return mode != Mode.IDLE && mode != Mode.PAUSED; }

    private Kind kindAt(ClientLevel level, BlockPos pos) {
        var block = level.getBlockState(pos).getBlock();
        if (block instanceof ShulkerBoxBlock) return Kind.SHULKER;
        if (block instanceof ChestBlock) return Kind.CHEST;
        return null;
    }

    private BlockPos lookedAtChest() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit)
            || hit.getType() != HitResult.Type.BLOCK) {
            tell("Look directly at a placed chest or shulker box first.");
            return null;
        }
        return hit.getBlockPos().immutable();
    }

    private void registerStorage() {
        if (busy()) { tell("Pause or stop before editing storage."); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BlockPos pos = lookedAtChest();
        if (pos == null) return;
        Kind kind = kindAt(mc.level, pos);
        if (kind == null) { tell("Target must be a placed chest or shulker box."); return; }
        if (destinations.stream().anyMatch(t -> t.pos().equals(pos))) {
            tell("This container is already registered."); return;
        }
        destinations.add(new Target(pos, kind));
        tell("Storage #" + destinations.size() + ": " + kind + " at " + pos);
    }

    private void removeStorage() {
        if (busy()) { tell("Pause or stop before editing storage."); return; }
        BlockPos pos = lookedAtChest();
        if (pos == null) return;
        if (destinations.removeIf(t -> t.pos().equals(pos))) tell("Removed storage at " + pos);
        else tell("That block was not a registered destination.");
    }

    private void clearStorage() {
        if (busy()) { tell("Pause or stop before editing storage."); return; }
        destinations.clear(); tell("Storage destinations cleared.");
    }

    private void listStorage() {
        if (destinations.isEmpty()) { tell("No storage set; look at a container and /excavate storage add."); return; }
        for (int i = 0; i < destinations.size(); i++) {
            Target t = destinations.get(i);
            tell("#" + (i + 1) + " " + t.kind() + " " + t.pos());
        }
    }

    private void status() {
        tell("State=" + mode + ", corners=" + corner1 + " -> " + corner2
            + ", destinations=" + destinations.size() + ", active=" + (activeDestination + 1)
            + ", free slots=" + emptySlots() + ", stacks transferred=" + transferCount);
    }

    private void begin() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (corner1 == null || corner2 == null || destinations.isEmpty()) {
            tell("Set pos1, pos2 and at least one chest/shulker with /excavate storage add."); return;
        }
        if (emptySlots() <= RESERVE_SLOTS) {
            tell("Inventory near capacity. Use /excavate unload or clear inventory first."); return;
        }
        startRegion();
    }

    private void startRegion() {
        try {
            BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().clearArea(corner1, corner2);
            enter(Mode.MINING);
            tell("Mining " + corner1 + " -> " + corner2);
        } catch (RuntimeException ex) { fail("Baritone start failed: " + ex.getMessage()); }
    }

    private void beginUnloading() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (destinations.isEmpty()) { tell("Register a chest or shulker box first."); return; }
        if (busy() && mode != Mode.MINING) { tell("Storage run already in progress."); return; }
        if (!hasDepositable()) { tell("No eligible main-inventory items to deposit."); return; }
        cancelBaritone();
        transferCount = 0;
        activeDestination = -1;
        routeNext();
    }

    private void routeNext() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { fail("Player unavailable."); return; }
        if (activeDestination + 1 >= destinations.size()) {
            fail("All storage options unavailable/full; inventory left unchanged where possible."); return;
        }
        activeDestination++;
        Target target = destinations.get(activeDestination);
        if (kindAt(mc.level, target.pos()) != target.kind()) {
            tell("Storage #" + (activeDestination + 1) + " missing or changed; trying next.");
            routeNext(); return;
        }
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
        try {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                .setGoalAndPath(new GoalNear(target.pos(), 2));
            enter(Mode.GOING_TO_STORAGE);
            tell("Navigating to " + target.kind() + " #" + (activeDestination + 1));
        } catch (RuntimeException ex) {
            tell("Path failed: " + ex.getMessage());
            routeNext();
        }
    }

    private void tick(Minecraft mc) {
        if (mc.level != attachedLevel) {
            if (attachedLevel != null) {
                cancelBaritone();
                corner1 = null; corner2 = null; destinations.clear();
                enter(Mode.IDLE);
            }
            attachedLevel = mc.level;
        }
        if (mc.level == null || mc.player == null) return;
        if (mode == Mode.IDLE || mode == Mode.PAUSED) return;
        stateTicks++;
        heartbeat++;
        if (mode == Mode.MINING) {
            if (heartbeat % 20 == 0 && emptySlots() <= RESERVE_SLOTS) {
                if (hasDepositable()) beginUnloading();
                else fail("Inventory full but no allowed items to deposit. Unload manually.");
            }
            return;
        }
        if (mode == Mode.GOING_TO_STORAGE) {
            if (mc.gui.screen() != null) { fail("Unexpected screen during navigation."); return; }
            Target target = destinations.get(activeDestination);
            if (kindAt(mc.level, target.pos()) != target.kind()) { cancelBaritone(); routeNext(); return; }
            if (mc.player.blockPosition().distSqr(target.pos()) <= 9) {
                cancelBaritone();
                var hit = new BlockHitResult(Vec3.atCenterOf(target.pos()).add(0, 0.5, 0),
                    Direction.UP, target.pos(), false);
                try {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                    enter(Mode.OPENING);
                } catch (RuntimeException ex) { tell("Could not open storage: " + ex.getMessage()); routeNext(); }
            } else if (stateTicks > PATH_TIMEOUT_TICKS) { cancelBaritone(); tell("Storage path timed out."); routeNext(); }
            return;
        }
        if (mode == Mode.OPENING) {
            if (matchingMenu(mc) != null) {
                enter(Mode.TRANSFERRING);
            } else if (stateTicks > OPEN_TIMEOUT_TICKS) {
                tell("Storage screen did not open; attempting next destination.");
                routeNext();
            }
            return;
        }
        if (mode == Mode.TRANSFERRING) transferTick(mc);
        if (mode == Mode.SETTLING && stateTicks >= 20) completeStorage(mc);
    }

    private AbstractContainerMenu matchingMenu(Minecraft mc) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return null;
        if (screen.getMenu() != mc.player.containerMenu) return null;
        AbstractContainerMenu menu = screen.getMenu();
        Kind kind = destinations.get(activeDestination).kind();
        if (kind == Kind.CHEST && menu instanceof ChestMenu) return menu;
        if (kind == Kind.SHULKER && menu instanceof ShulkerBoxMenu) return menu;
        return null;
    }

    private int containerSize(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu chest ? chest.getRowCount() * 9 : 27;
    }

    private void transferTick(Minecraft mc) {
        AbstractContainerMenu menu = matchingMenu(mc);
        if (menu == null) { fail("Storage menu closed unexpectedly."); return; }
        if (!menu.getCarried().isEmpty()) { fail("Cursor holds an item; close and recover manually."); return; }
        if (stateTicks % CLICK_DELAY_TICKS != 0) return;

        if (pendingSlot >= 0) {
            ItemStack current = menu.getSlot(pendingSlot).getItem();
            boolean unchanged = !current.isEmpty()
                && ItemStack.isSameItemSameComponents(current, pendingItem)
                && current.getCount() >= pendingCount;
            pendingSlot = -1;
            if (unchanged) {
                tell("Transfer did not progress. Trying another destination.");
                routeNext();
                return;
            }
            movedThisChest = true;
            transferCount++;
        }

        int chestSlots = containerSize(menu);
        for (int i = chestSlots; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container != mc.player.getInventory()) continue;
            if (slot.getContainerSlot() < 9) continue; // Hotbar protected.
            ItemStack stack = slot.getItem();
            if (!depositable(stack)) continue;
            if (!hasCapacity(menu, chestSlots, stack)) continue;
            pendingSlot = i;
            pendingItem = stack.copy();
            pendingCount = stack.getCount();
            mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
            return; // One shift-click per transfer interval.
        }
        enter(Mode.SETTLING);
    }

    private boolean hasCapacity(AbstractContainerMenu menu, int chestSlots, ItemStack stack) {
        for (int i = 0; i < chestSlots; i++) {
            Slot s = menu.getSlot(i);
            if (!s.mayPlace(stack)) continue;
            ItemStack there = s.getItem();
            if (there.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(there, stack)
                && there.getCount() < Math.min(there.getMaxStackSize(), s.getMaxStackSize(there))) return true;
        }
        return false;
    }

    private void completeStorage(Minecraft mc) {
        if (matchingMenu(mc) == null) { fail("Storage screen closed before verification."); return; }
        if (mc.player != null) mc.player.closeContainer();
        pendingSlot = -1;
        if (emptySlots() >= RESUME_SLOTS && corner1 != null && corner2 != null) {
            tell("Deposited " + transferCount + " stacks; resuming excavation.");
            startRegion();
        } else if (hasDepositable()) {
            tell("Need more empty slots. Trying another container.");
            routeNext();
        } else {
            fail("Only " + emptySlots() + " slots free; remaining items are protected or not on the resource allowlist.");
        }
    }

    private boolean depositable(ItemStack stack) {
        if (stack.isEmpty() || stack.isDamageableItem()) return false;
        if (stack.getItem() instanceof BlockItem item) {
            return !(item.getBlock() instanceof ShulkerBoxBlock)
                && !(item.getBlock() instanceof ChestBlock);
        }
        return ORE_DROPS.contains(stack.getItem());
    }

    private boolean hasDepositable() {
        var p = Minecraft.getInstance().player;
        if (p == null) return false;
        for (int i = 9; i < 36; i++) if (depositable(p.getInventory().getItem(i))) return true;
        return false;
    }

    private int emptySlots() {
        var p = Minecraft.getInstance().player;
        if (p == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) count++;
        return count;
    }

    private void resume() {
        if (mode != Mode.PAUSED) { tell("Pause first."); return; }
        begin();
    }

    private void pause() {
        cancelBaritone();
        cleanupMenu();
        enter(Mode.PAUSED);
        tell("Paused.");
    }

    private void stop() {
        cancelBaritone();
        cleanupMenu();
        enter(Mode.IDLE);
        tell("Stopped.");
    }

    private void fail(String message) {
        cancelBaritone();
        cleanupMenu();
        enter(Mode.PAUSED);
        tell("PAUSED: " + message);
    }

    private void cleanupMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && (mode == Mode.OPENING || mode == Mode.TRANSFERRING || mode == Mode.SETTLING)
            && mc.gui.screen() instanceof AbstractContainerScreen<?>)
            mc.player.closeContainer();
        pendingSlot = -1;
    }

    private void enter(Mode next) {
        mode = next;
        stateTicks = 0;
        if (next == Mode.TRANSFERRING) { pendingSlot = -1; movedThisChest = false; }
    }

    private void cancelBaritone() {
        try { BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything(); }
        catch (RuntimeException ignored) { }
    }

    private void tell(String text) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.sendSystemMessage(Component.literal("[Excavate] " + text));
    }
}

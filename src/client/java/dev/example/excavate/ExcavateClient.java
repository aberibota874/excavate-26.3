package dev.example.excavate;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Prototype: chest waypoint navigation only; chest inventory transfers not implemented.
 */
public final class ExcavateClient implements ClientModInitializer {
    private enum Mode { IDLE, MINING, GOING_TO_STORAGE, AWAITING_UNLOAD, PAUSED }

    private BlockPos first, second, storage;
    private Mode mode = Mode.IDLE;
    private final int reserveSlots = 3;
    private int heartbeat = 0;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            dispatcher.register(ClientCommands.literal("excavate")
                .then(ClientCommands.literal("pos1").executes(ctx -> { first = playerPos(); tell("First corner saved: " + first); return 1; }))
                .then(ClientCommands.literal("pos2").executes(ctx -> { second = playerPos(); tell("Second corner saved: " + second); return 1; }))
                .then(ClientCommands.literal("chest").executes(ctx -> { storage = playerPos(); tell("Storage waypoint saved: " + storage + " (stand adjacent to a chest)"); return 1; }))
                .then(ClientCommands.literal("start").executes(ctx -> { begin(); return 1; }))
                .then(ClientCommands.literal("resume").executes(ctx -> { resume(); return 1; }))
                .then(ClientCommands.literal("pause").executes(ctx -> { pause(); return 1; }))
                .then(ClientCommands.literal("stop").executes(ctx -> { stop(); return 1; }))
                .then(ClientCommands.literal("status").executes(ctx -> { tell("State=" + mode + "; corners=" + first + " -> " + second + "; storage=" + storage + "; empty slots=" + emptySlots()); return 1; }))
            );
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private void begin() {
        if (first == null || second == null || storage == null) {
            tell("Set /excavate pos1, /excavate pos2, and /excavate chest first.");
            return;
        }
        if (Minecraft.getInstance().player == null) return;
        if (emptySlots() <= reserveSlots) { tell("Unload inventory before starting."); return; }
        startRegion();
    }

    private void startRegion() {
        try {
            BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().clearArea(first, second);
            mode = Mode.MINING;
            tell("Excavating selected region. Test in a safe area.");
        } catch (RuntimeException ex) { mode = Mode.PAUSED; tell("Baritone region start failed: " + ex.getMessage()); }
    }

    private void tick() {
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) {
            if (mode != Mode.IDLE) { cancelBaritone(); mode = Mode.PAUSED; }
            return;
        }
        if (++heartbeat % 20 != 0) return;
        if (mode == Mode.MINING && emptySlots() <= reserveSlots) {
            cancelBaritone();
            try {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                    .setGoalAndPath(new GoalNear(storage, 2));
                mode = Mode.GOING_TO_STORAGE;
                tell("Inventory nearing capacity; moving to storage waypoint.");
            } catch (RuntimeException ex) { mode = Mode.PAUSED; tell("Storage path failed: " + ex.getMessage()); }
        } else if (mode == Mode.GOING_TO_STORAGE && playerPos().distSqr(storage) <= 9) {
            cancelBaritone();
            mode = Mode.AWAITING_UNLOAD;
            tell("At storage. UNLOAD MANUALLY in v0.1, then use /excavate resume.");
        }
    }

    private void resume() {
        if (mode != Mode.AWAITING_UNLOAD && mode != Mode.PAUSED) { tell("Nothing to resume."); return; }
        if (emptySlots() <= reserveSlots) { tell("Inventory still full; unload first."); return; }
        if (first == null || second == null) { tell("Set corners first."); return; }
        startRegion();
    }

    private void pause() { cancelBaritone(); mode = Mode.PAUSED; tell("Excavation paused."); }
    private void stop() { cancelBaritone(); mode = Mode.IDLE; tell("Excavation stopped."); }
    private void cancelBaritone() {
        try { BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything(); }
        catch (RuntimeException ignored) { }
    }
    private int emptySlots() {
        var p = Minecraft.getInstance().player;
        if (p == null) return 0;
        int free = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) free++;
        return free;
    }
    private BlockPos playerPos() {
        var p = Minecraft.getInstance().player;
        return p == null ? BlockPos.ZERO : p.blockPosition();
    }
    private void tell(String text) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal("[Excavate] " + text), false);
    }
}

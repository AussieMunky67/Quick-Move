package com.example.hoverquickmove;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side mod: while you hold the activation key (Tab by default) in any container screen
 * (inventory, chest, shulker box, furnace ...), just moving the mouse over an item shift-clicks it,
 * sending it to the other side, with no clicking needed.
 *
 * Settings live in config/hoverquickmove.properties (created on first launch):
 *   key        = TAB, LALT, LCONTROL, CAPSLOCK, SPACE, or a single letter such as X  (default TAB)
 *   delayTicks = game ticks to wait between moves, 20 ticks = 1 second (default 2)
 * The file is re-read automatically every couple of seconds.
 */
public class HoverQuickMoveClient implements ClientModInitializer {
    private static int keyCode = InputConstants.KEY_TAB;
    private static int delayTicks = 2;
    private static Path configPath;
    private static long configModified = -1;
    private static long lastConfigCheck = 0;
    private static int cooldown = 0;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("hoverquickmove.properties");
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(HoverQuickMoveClient::tick);
    }

    private static void tick(Minecraft mc) {
        reloadIfChanged();
        if (cooldown > 0) cooldown--;

        if (mc.player == null || mc.gameMode == null) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return;
        if (screen instanceof CreativeModeInventoryScreen) return;   // creative inventory works differently
        if (!InputConstants.isKeyDown(mc.getWindow(), keyCode)) return;
        if (cooldown > 0) return;

        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || !slot.hasItem()) return;
        if (slot instanceof ResultSlot) return;   // crafting output: click it yourself so it doesn't spam-craft

        // exactly what shift-clicking the slot does
        mc.gameMode.handleContainerInput(
            screen.getMenu().containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
        cooldown = Math.max(0, delayTicks - 1);
    }

    private static int keyFromName(String name) {
        return switch (name.trim().toUpperCase()) {
            case "TAB" -> InputConstants.KEY_TAB;
            case "LALT" -> InputConstants.KEY_LALT;
            case "LCONTROL" -> InputConstants.KEY_LCONTROL;
            case "CAPSLOCK" -> InputConstants.KEY_CAPSLOCK;
            case "SPACE" -> InputConstants.KEY_SPACE;
            case "X" -> InputConstants.KEY_X;
            case "Z" -> InputConstants.KEY_Z;
            case "C" -> InputConstants.KEY_C;
            case "V" -> InputConstants.KEY_V;
            case "G" -> InputConstants.KEY_G;
            case "F" -> InputConstants.KEY_F;
            case "Q" -> InputConstants.KEY_Q;
            default -> InputConstants.KEY_TAB;
        };
    }

    private static void loadConfig() {
        try {
            if (!Files.exists(configPath)) {
                Properties defaults = new Properties();
                defaults.setProperty("key", "TAB");
                defaults.setProperty("delayTicks", "2");
                try (OutputStream out = Files.newOutputStream(configPath)) {
                    defaults.store(out, "Hover Quick Move. key = TAB, LALT, LCONTROL, CAPSLOCK, SPACE, X, Z, C, V, G, F or Q. delayTicks = ticks between moves (20 ticks = 1 second).");
                }
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            }
            keyCode = keyFromName(p.getProperty("key", "TAB"));
            try {
                delayTicks = Math.max(1, Math.min(40, Integer.parseInt(p.getProperty("delayTicks", "2").trim())));
            } catch (NumberFormatException e) {
                delayTicks = 2;
            }
            configModified = Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException | RuntimeException ignored) {
            // keep previous settings if the file can't be read
        }
    }

    private static void reloadIfChanged() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheck < 2000) return;
        lastConfigCheck = now;
        try {
            if (Files.exists(configPath) && Files.getLastModifiedTime(configPath).toMillis() != configModified) {
                loadConfig();
            }
        } catch (IOException ignored) {
        }
    }
}

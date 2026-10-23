package com.example.hoverquickmove;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side mod: while you hold the activation key (Tab by default) in any container screen
 * (inventory, chest, shulker box, furnace ...), just moving the mouse over an item shift-clicks it,
 * sending it to the other side, with no clicking needed.
 *
 * It is checked every rendered frame (and every tick as a backup), so items move the instant
 * the cursor touches them, exactly like a shift-click.
 *
 * Settings live in config/hoverquickmove.properties (created on first launch):
 *   key     = TAB, LALT, LCONTROL, CAPSLOCK, SPACE, or a single letter such as X  (default TAB)
 *   delayMs = minimum milliseconds between moves, 0 = as fast as possible (default 0)
 * The file is re-read automatically every couple of seconds.
 */
public class HoverQuickMoveClient implements ClientModInitializer {
    private static int keyCode = InputConstants.KEY_TAB;
    private static long delayMs = 0;
    private static Path configPath;
    private static long configModified = -1;
    private static long lastConfigCheck = 0;
    private static long lastMoveNanos = 0;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("hoverquickmove.properties");
        loadConfig();

        // every rendered frame: this is what makes it feel instant
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("hoverquickmove", "hover_move"),
            HoverQuickMoveClient::onFrame
        );
        // every tick: backup in case frames are not delivered while a screen is open
        ClientTickEvents.END_CLIENT_TICK.register(HoverQuickMoveClient::tryMove);
    }

    private static void onFrame(GuiGraphicsExtractor g, DeltaTracker delta) {
        tryMove(Minecraft.getInstance());
    }

    private static void tryMove(Minecraft mc) {
        reloadIfChanged();

        if (mc.player == null || mc.gameMode == null) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
        if (screen instanceof CreativeModeInventoryScreen) return;   // creative inventory works differently
        if (!InputConstants.isKeyDown(mc.getWindow(), keyCode)) return;

        long now = System.nanoTime();
        if (now - lastMoveNanos < delayMs * 1_000_000L) return;

        Slot slot = getHoveredSlot(screen);
        if (slot == null || !slot.hasItem()) return;
        if (slot instanceof ResultSlot) return;   // crafting output: click it yourself so it doesn't spam-craft

        // exactly what shift-clicking the slot does
        mc.gameMode.handleContainerInput(
            screen.getMenu().containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
        lastMoveNanos = now;
    }

    private static Field hoveredField;
    private static boolean hoveredLookupFailed = false;

    /** The slot the mouse is over. Read through reflection because the field is protected. */
    private static Slot getHoveredSlot(AbstractContainerScreen<?> screen) {
        if (hoveredLookupFailed) return null;
        try {
            if (hoveredField == null) {
                for (Field f : AbstractContainerScreen.class.getDeclaredFields()) {
                    if (f.getType() == Slot.class && f.getName().toLowerCase().contains("hover")) {
                        hoveredField = f;
                        break;
                    }
                }
                if (hoveredField == null) {
                    hoveredLookupFailed = true;
                    System.out.println("[HoverQuickMove] could not find the hovered-slot field");
                    return null;
                }
                hoveredField.setAccessible(true);
            }
            return (Slot) hoveredField.get(screen);
        } catch (Exception e) {
            hoveredLookupFailed = true;
            System.out.println("[HoverQuickMove] could not read the hovered slot: " + e);
            return null;
        }
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
                defaults.setProperty("delayMs", "0");
                try (OutputStream out = Files.newOutputStream(configPath)) {
                    defaults.store(out, "Hover Quick Move. key = TAB, LALT, LCONTROL, CAPSLOCK, SPACE, X, Z, C, V, G, F or Q. delayMs = minimum milliseconds between moves (0 = instant).");
                }
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            }
            keyCode = keyFromName(p.getProperty("key", "TAB"));
            try {
                delayMs = Math.max(0, Math.min(1000, Long.parseLong(p.getProperty("delayMs", "0").trim())));
            } catch (NumberFormatException e) {
                delayMs = 0;
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

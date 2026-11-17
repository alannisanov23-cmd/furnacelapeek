package dev.furnacepeek;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Furnace Peek (client-only).
 *
 * The server never tells the client how far along a furnace is unless you have its screen open, so this mod
 * works like this: every time you open a furnace / smoker / blast furnace it takes a SNAPSHOT (progress, items
 * left, fuel, output). When you later look at that furnace it combines the snapshot with the live LIT state
 * of the block and the game clock to show a status and a time estimate. Snapshots live in memory only (they are
 * forgotten when you quit the game).
 *
 * Text only: no mixins, no rendering changes, so it coexists with any other mod or resource pack.
 */
public class FurnacePeekClient implements ClientModInitializer {

    /** What we learned the last time you had this furnace open. */
    record Snap(long gameTime, boolean lit, int total, int inputCount, int remaining, int fuelCount, int outputCount) {}

    record Line(String text, int rgb) {}

    private static final Map<String, Snap> SNAPS = new HashMap<>();

    // Furnace the player is currently looking at (null when none).
    private static String lookedKey = null;
    private static boolean lookedLit = false;
    private static String lookedName = "Furnace";

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(FurnacePeekClient::tick);
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath("furnacepeek", "peek"),
                (graphics, tickCounter) -> draw(graphics));
    }

    // ------------------------------------------------------------------ tracking

    private static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            lookedKey = null;
            return;
        }

        // Furnace screen open: keep refreshing the snapshot for the furnace we were looking at.
        if (player.containerMenu instanceof AbstractFurnaceMenu menu) {
            if (lookedKey != null) snapshot(level, menu, lookedKey);
            return;
        }

        // Otherwise find out whether we're looking at a furnace.
        lookedKey = null;
        HitResult hit = player.pick(5.0, 1.0f, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof AbstractFurnaceBlock) {
                lookedKey = level.dimension() + ":" + pos.asLong();
                lookedLit = state.getValue(BlockStateProperties.LIT);
                lookedName = state.getBlock().getName().getString();
            }
        }
    }

    private static void snapshot(ClientLevel level, AbstractFurnaceMenu menu, String key) {
        // Smokers and blast furnaces smelt twice as fast as a furnace (100 vs 200 ticks per item).
        int total = (menu instanceof SmokerMenu || menu instanceof BlastFurnaceMenu) ? 100 : 200;
        int progress = (int) Math.round(menu.getBurnProgress() / 24.0 * total);

        ItemStack input = menu.getSlot(0).getItem();
        ItemStack fuel = menu.getSlot(1).getItem();
        ItemStack output = menu.getSlot(2).getItem();

        int inputCount = input.isEmpty() ? 0 : input.getCount();
        int remaining = inputCount > 0 ? (inputCount - 1) * total + (total - progress) : 0;

        SNAPS.put(key, new Snap(
                level.getGameTime(),
                menu.isLit(),
                total,
                inputCount,
                remaining,
                fuel.isEmpty() ? 0 : fuel.getCount(),
                output.isEmpty() ? 0 : output.getCount()));
    }

    // ------------------------------------------------------------------ display

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || lookedKey == null) return;

        Snap s = SNAPS.get(lookedKey);
        List<Line> lines = new ArrayList<>();
        double fraction = -1.0;   // < 0 means "no progress bar"
        int barColor = 0xFFA030;

        lines.add(new Line(lookedName + (lookedLit ? " - burning" : " - not burning"),
                lookedLit ? 0xFFC060 : 0xFFFFFF));

        if (s == null) {
            lines.add(new Line("Open it once to start tracking", 0xAAAAAA));
        } else {
            long elapsed = Math.max(0L, mc.level.getGameTime() - s.gameTime());
            long remaining = s.remaining();
            String status;
            int color;

            if (s.inputCount() == 0) {
                status = "Nothing smelting";
                color = 0xAAAAAA;
                fraction = -1.0;
            } else if (lookedLit && s.lit()) {
                remaining = Math.max(0L, s.remaining() - elapsed);
                if (remaining == 0L) {
                    status = "Done - check the output";
                    color = 0x6BFF6B;
                    barColor = 0x6BFF6B;
                } else {
                    status = "Smelting - about " + fmt(remaining) + " left";
                    color = 0xFFC060;
                }
                fraction = 1.0 - remaining / (double) (s.inputCount() * s.total());
            } else if (lookedLit) {
                status = "Burning now - open it to refresh";
                color = 0xFFE070;
                fraction = -1.0;
            } else if (s.lit()) {
                if (elapsed >= s.remaining()) {
                    status = "Finished";
                    color = 0x6BFF6B;
                    barColor = 0x6BFF6B;
                    fraction = 1.0;
                } else {
                    remaining = s.remaining() - elapsed;
                    status = "Stopped - out of fuel? about " + fmt(remaining) + " of work left";
                    color = 0xFF6B6B;
                    barColor = 0xFF6B6B;
                    fraction = 1.0 - remaining / (double) (s.inputCount() * s.total());
                }
            } else {
                status = "Needs fuel - " + fmt(s.remaining()) + " of work waiting";
                color = 0xFF6B6B;
                barColor = 0xFF6B6B;
                fraction = 1.0 - s.remaining() / (double) (s.inputCount() * s.total());
            }
            lines.add(new Line(status, color));
            lines.add(new Line("Fuel left: " + s.fuelCount() + "   Output: " + s.outputCount()
                    + "   (opened " + fmtAgo(elapsed) + " ago)", 0x9A9A9A));
        }

        // Layout: centred under the crosshair.
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int textWidth = 80;
        for (Line l : lines) textWidth = Math.max(textWidth, mc.font.width(l.text()));
        int boxW = textWidth + 10;
        int boxH = lines.size() * 11 + 6 + (fraction >= 0.0 ? 8 : 0);
        int x0 = w / 2 - boxW / 2;
        int y0 = h / 2 + 14;

        g.fill(x0, y0, x0 + boxW, y0 + boxH, 0xB0000000);
        g.fill(x0, y0, x0 + boxW, y0 + 1, 0xFF000000 | barColor);   // coloured top edge

        int y = y0 + 4;
        for (Line l : lines) {
            g.text(mc.font, l.text(), x0 + 5, y, 0xFF000000 | l.rgb(), true);
            y += 11;
        }

        if (fraction >= 0.0) {
            int barX = x0 + 5;
            int barW = boxW - 10;
            int fill = (int) Math.round(barW * Math.max(0.0, Math.min(1.0, fraction)));
            g.fill(barX, y, barX + barW, y + 4, 0xFF303030);
            g.fill(barX, y, barX + fill, y + 4, 0xFF000000 | barColor);
        }
    }

    private static String fmt(long ticks) {
        long seconds = (ticks + 19L) / 20L;
        return seconds >= 60L ? String.format("%d:%02d", seconds / 60L, seconds % 60L) : seconds + " s";
    }

    private static String fmtAgo(long ticks) {
        long seconds = ticks / 20L;
        return seconds >= 60L ? String.format("%d:%02d", seconds / 60L, seconds % 60L) : seconds + " s";
    }
}

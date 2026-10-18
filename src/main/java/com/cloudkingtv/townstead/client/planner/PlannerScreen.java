package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.client.TownsteadKeys;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The planner's input and UI layer. It never pauses the game, keeps the mouse free as a cursor, steers
 * {@link PlannerCamera} with WASD/QE/scroll, and draws the town bar and tool palette.
 */
public class PlannerScreen extends Screen {
    private static final Identifier PANEL = Townstead.id("planner/panel");
    private static final Identifier BUTTON = Townstead.id("planner/button");
    private static final Identifier BUTTON_HOVER = Townstead.id("planner/button_hover");
    private static final Identifier BUTTON_SELECTED = Townstead.id("planner/button_selected");
    private static final int TOP_BAR_H = 22, SLOT = 24, TOOLBAR_PAD = 5;

    private record Tool(String key, ItemStack icon, boolean available) {}

    private static final List<Tool> TOOLS = List.of(
            new Tool("inspect", new ItemStack(Items.SPYGLASS), true),
            new Tool("roads", new ItemStack(Items.DIRT_PATH), false),
            new Tool("zoning", new ItemStack(Items.OAK_SIGN), false),
            new Tool("utilities", new ItemStack(Items.REDSTONE_TORCH), false),
            new Tool("services", new ItemStack(Items.BELL), false),
            new Tool("transport", new ItemStack(Items.MINECART), false),
            new Tool("parks", new ItemStack(Items.OAK_SAPLING), false),
            new Tool("districts", new ItemStack(Items.MAP), false),
            new Tool("bulldoze", new ItemStack(Items.IRON_SHOVEL), false));

    private final long openedAt = Util.getMillis();
    private final float[] hover = new float[TOOLS.size()];
    private int selectedTool = 0;
    private boolean rotating;

    public PlannerScreen() {
        super(Component.translatable("screen.townstead.planner"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No dimming or blur: the world is the canvas.
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE || TownsteadKeys.PLANNER.matches(event)) {
            PlannerClient.requestExit();
            return true;
        }
        return setHeld(event.key(), true) || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        return setHeld(event.key(), false) || super.keyReleased(event);
    }

    private boolean setHeld(int key, boolean down) {
        PlannerCamera cam = PlannerClient.CAMERA;
        switch (key) {
            case InputConstants.KEY_W, InputConstants.KEY_UP -> cam.forward = down;
            case InputConstants.KEY_S, InputConstants.KEY_DOWN -> cam.back = down;
            case InputConstants.KEY_A, InputConstants.KEY_LEFT -> cam.left = down;
            case InputConstants.KEY_D, InputConstants.KEY_RIGHT -> cam.right = down;
            case InputConstants.KEY_Q -> cam.rotateLeft = down;
            case InputConstants.KEY_E -> cam.rotateRight = down;
            default -> { return false; }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        PlannerClient.CAMERA.zoom(scrollY);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int tool = toolAt(event.x(), event.y());
        if (tool >= 0) {
            if (TOOLS.get(tool).available()) {
                selectedTool = tool;
                click(1.0f);
            } else {
                click(0.6f);
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE || event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            rotating = true;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && PlannerClient.hoverCell != null) {
            PlannerClient.selectedCell = PlannerClient.hoverCell;
            click(1.2f);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        rotating = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (rotating) {
            PlannerClient.CAMERA.rotate((float) dx * 0.5f);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public void removed() {
        PlannerCamera cam = PlannerClient.CAMERA;
        cam.forward = cam.back = cam.left = cam.right = cam.rotateLeft = cam.rotateRight = false;
        super.removed();
    }

    private void click(float pitch) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.4f));
    }

    // ---------------------------------------------------------------- cursor

    private void updateHover(int mouseX, int mouseY) {
        if (mouseY < TOP_BAR_H || toolAt(mouseX, mouseY) >= 0 || minecraft.level == null) {
            PlannerClient.hoverCell = null;
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        float ndcX = (float) mouseX / width * 2f - 1f;
        float ndcY = 1f - (float) mouseY / height * 2f;
        Vec3 dir = camera.getNearPlane(camera.getFov()).getPointOnPlane(ndcX, ndcY).normalize();
        Vec3 from = camera.position();
        BlockHitResult hit = minecraft.level.clip(new ClipContext(from, from.add(dir.scale(400)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, minecraft.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            PlannerClient.hoverCell = null;
            return;
        }
        BlockPos p = hit.getBlockPos();
        int cx = Math.floorDiv(p.getX(), TilePos.CELL) * TilePos.CELL;
        int cz = Math.floorDiv(p.getZ(), TilePos.CELL) * TilePos.CELL;
        int y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + 4, cz + 4);
        PlannerClient.hoverCell = new BlockPos(cx, y, cz);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        updateHover(mouseX, mouseY);
        float open = Mth.clamp((Util.getMillis() - openedAt) / 220f, 0f, 1f);
        float slide = 1f - (1f - open) * (1f - open); // ease-out
        CitySummary city = PlannerClient.city().orElse(null);

        // Top bar slides down, toolbar slides up.
        g.pose().pushMatrix();
        g.pose().translate(0, (slide - 1f) * TOP_BAR_H);
        drawTopBar(g, city);
        g.pose().popMatrix();

        int barW = TOOLS.size() * SLOT + TOOLBAR_PAD * 2;
        int barX = (width - barW) / 2, barY = height - SLOT - TOOLBAR_PAD * 2 - 4;
        g.pose().pushMatrix();
        g.pose().translate(0, (1f - slide) * (SLOT + 16));
        drawToolbar(g, barX, barY, barW, mouseX, mouseY);
        g.pose().popMatrix();

        drawCursorInfo(g, city, mouseX, mouseY);
        drawHints(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawTopBar(GuiGraphicsExtractor g, CitySummary city) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, -4, -4, width + 8, TOP_BAR_H + 4);
        if (city == null) return;
        int y = (TOP_BAR_H - font.lineHeight) / 2;
        g.item(new ItemStack(Items.BELL), 6, 3);
        g.text(font, Component.literal(city.name()), 26, y, 0xFFFFE9B0, true);
        int x = width - 8;
        x = rightStat(g, x, y, new ItemStack(Items.FILLED_MAP), Component.translatable("hud.townstead.tiles", city.tiles().size()));
        x = rightStat(g, x, y, new ItemStack(Items.VILLAGER_SPAWN_EGG), Component.translatable("hud.townstead.population", 0));
        rightStat(g, x, y, new ItemStack(Items.EMERALD), Component.literal(String.valueOf(city.treasury())));
    }

    private int rightStat(GuiGraphicsExtractor g, int right, int y, ItemStack icon, Component text) {
        int w = font.width(text);
        g.text(font, text, right - w, y, 0xFFF2F2F2, true);
        g.item(icon, right - w - 19, 3);
        return right - w - 32;
    }

    private void drawToolbar(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, w, SLOT + TOOLBAR_PAD * 2);
        int hovered = toolAt(mouseX, mouseY);
        for (int i = 0; i < TOOLS.size(); i++) {
            Tool tool = TOOLS.get(i);
            int bx = x + TOOLBAR_PAD + i * SLOT, by = y + TOOLBAR_PAD;
            hover[i] += ((i == hovered ? 1f : 0f) - hover[i]) * 0.3f;
            int lift = Math.round(hover[i] * 2f);
            Identifier sprite = i == selectedTool ? BUTTON_SELECTED : i == hovered ? BUTTON_HOVER : BUTTON;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, bx + 1, by + 1 - lift, SLOT - 2, SLOT - 2,
                    tool.available() ? 0xFFFFFFFF : 0xFF8A8A8A);
            g.item(tool.icon(), bx + 4, by + 4 - lift);
            if (!tool.available()) g.fill(bx + 2, by + 2 - lift, bx + SLOT - 2, by + SLOT - 2 - lift, 0x66101010);
        }
        if (hovered >= 0) {
            Tool tool = TOOLS.get(hovered);
            Component name = Component.translatable("tool.townstead." + tool.key());
            g.setTooltipForNextFrame(font, tool.available() ? List.of(name.getVisualOrderText())
                    : List.of(name.getVisualOrderText(), Component.translatable("tool.townstead.locked").getVisualOrderText()),
                    mouseX, mouseY);
        }
    }

    private void drawCursorInfo(GuiGraphicsExtractor g, CitySummary city, int mouseX, int mouseY) {
        BlockPos cell = PlannerClient.selectedCell != null ? PlannerClient.selectedCell : PlannerClient.hoverCell;
        if (cell == null) return;
        TilePos tile = TilePos.ofBlock(cell.getX(), cell.getZ());
        boolean owned = city != null && city.tiles().contains(tile);
        Component line1 = Component.translatable("hud.townstead.cell", Math.floorDiv(cell.getX(), TilePos.CELL),
                Math.floorDiv(cell.getZ(), TilePos.CELL));
        Component line2 = Component.translatable(owned ? "hud.townstead.tile_owned" : "hud.townstead.tile_unowned",
                tile.x(), tile.z());
        int w = Math.max(font.width(line1), font.width(line2)) + 12;
        int x = 6, y = TOP_BAR_H + 6;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, w, 30);
        g.text(font, line1, x + 6, y + 6, 0xFFFFFFFF, true);
        g.text(font, line2, x + 6, y + 17, owned ? 0xFF9EE37D : 0xFFE0A060, true);
    }

    private void drawHints(GuiGraphicsExtractor g) {
        // Just under the top bar on the right, clear of the toolbar at any GUI scale.
        Component hint = Component.translatable("hud.townstead.planner_hint");
        int w = font.width(hint);
        g.text(font, hint, width - w - 6, TOP_BAR_H + 6, ARGB.color(190, 255, 255, 255), true);
    }

    private int toolAt(double mouseX, double mouseY) {
        int barW = TOOLS.size() * SLOT + TOOLBAR_PAD * 2;
        int barX = (width - barW) / 2, barY = height - SLOT - TOOLBAR_PAD * 2 - 4;
        if (mouseY < barY + TOOLBAR_PAD || mouseY >= barY + TOOLBAR_PAD + SLOT) return -1;
        int i = (int) Math.floor((mouseX - barX - TOOLBAR_PAD) / SLOT);
        return i >= 0 && i < TOOLS.size() ? i : -1;
    }
}

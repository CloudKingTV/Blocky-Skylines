package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.client.TownsteadKeys;
import com.cloudkingtv.townstead.network.RenameTownPayload;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
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
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The planner's input and UI layer. It never pauses the game and keeps the mouse free as a cursor.
 * Controls follow Cities: Skylines: WASD pan (Shift faster), Q/E rotate, scroll or Z/X (R/F) zoom, Home/End (T/G)
 * tilt, middle-drag orbit, right-click or Esc cancel, B bulldoze. M leaves the planner.
 */
public class PlannerScreen extends Screen {
    private static final Identifier PANEL = Townstead.id("planner/panel");
    private static final Identifier BUTTON = Townstead.id("planner/button");
    private static final Identifier BUTTON_HOVER = Townstead.id("planner/button_hover");
    private static final Identifier BUTTON_SELECTED = Townstead.id("planner/button_selected");
    private static final int TOP_BAR_H = 22, SLOT = 24, TOOLBAR_PAD = 5, NAME_X = 26;
    private static final long HINT_MILLIS = 10_000, FLASH_MILLIS = 2_500;

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
    private static final int INSPECT = 0, BULLDOZE = 8;

    private final long openedAt = Util.getMillis();
    private final float[] hover = new float[TOOLS.size()];
    private int selectedTool = INSPECT;
    private boolean orbiting, showHelp;
    private @Nullable EditBox renameBox;
    private @Nullable Component flash;
    private long flashAt;

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

    @Override
    protected void init() {
        // Re-created on resize; keep an open rename box at the new layout.
        if (renameBox != null) {
            String value = renameBox.getValue();
            renameBox = null;
            openRename(value);
        }
    }

    // ---------------------------------------------------------------- keyboard

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (renameBox != null) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) { submitRename(); return true; }
            if (key == InputConstants.KEY_ESCAPE) { closeRename(); return true; }
            return super.keyPressed(event); // typing goes to the box, never to the camera
        }
        if (TownsteadKeys.PLANNER.matches(event)) { PlannerClient.requestExit(); return true; }
        if (key == InputConstants.KEY_ESCAPE) { cancelOrExit(); return true; }
        if (key == InputConstants.KEY_B) { pickTool(BULLDOZE); return true; }
        if (key == InputConstants.KEY_H) { showHelp = !showHelp; return true; }
        return setHeld(key, true) || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        // Always release, even while typing, so a key held when the box opened can't stick.
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
            case InputConstants.KEY_Z, InputConstants.KEY_R -> cam.zoomIn = down;
            case InputConstants.KEY_X, InputConstants.KEY_F -> cam.zoomOut = down;
            case InputConstants.KEY_HOME, InputConstants.KEY_T -> cam.tiltDown = down;
            case InputConstants.KEY_END, InputConstants.KEY_G -> cam.tiltUp = down;
            case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> cam.fast = down;
            default -> { return false; }
        }
        return true;
    }

    /** Esc / right-click: drop the selection, then the tool, and only then leave the planner (like CS). */
    private void cancelOrExit() {
        if (PlannerClient.selectedCell != null) {
            PlannerClient.selectedCell = null;
            click(0.8f);
        } else if (selectedTool != INSPECT) {
            selectedTool = INSPECT;
            click(0.8f);
        } else {
            PlannerClient.requestExit();
        }
    }

    // ---------------------------------------------------------------- mouse

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        PlannerClient.CAMERA.zoom(scrollY);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (renameBox != null) {
            if (renameBox.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
            submitRename(); // clicking away keeps what you typed, like most name fields
        }
        int button = event.button();
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) { orbiting = true; return true; }
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) { cancelOrExit(); return true; }
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);

        int tool = toolAt(event.x(), event.y());
        if (tool >= 0) { pickTool(tool); return true; }
        if (overName(event.x(), event.y())) {
            PlannerClient.city().ifPresent(c -> openRename(c.name()));
            click(1.0f);
            return true;
        }
        if (PlannerClient.hoverCell != null) {
            PlannerClient.selectedCell = PlannerClient.hoverCell;
            click(1.2f);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) orbiting = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (orbiting) {
            PlannerClient.CAMERA.rotate((float) dx * 0.45f);
            PlannerClient.CAMERA.tilt((float) dy * 0.3f);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public void removed() {
        PlannerClient.CAMERA.releaseAll();
        super.removed();
    }

    private void pickTool(int tool) {
        if (TOOLS.get(tool).available()) {
            selectedTool = tool;
            click(1.0f);
        } else {
            flash(Component.translatable("tool.townstead.locked_named", Component.translatable("tool.townstead." + TOOLS.get(tool).key())));
            click(0.6f);
        }
    }

    private void flash(Component message) {
        flash = message;
        flashAt = Util.getMillis();
    }

    private void click(float pitch) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.4f));
    }

    // ---------------------------------------------------------------- rename

    private boolean overName(double x, double y) {
        return PlannerClient.city().map(c -> y < TOP_BAR_H && x >= NAME_X - 20 && x <= NAME_X + font.width(c.name()) + 16)
                .orElse(false);
    }

    private void openRename(String initial) {
        if (renameBox != null) return;
        renameBox = new EditBox(font, NAME_X - 2, 4, 170, TOP_BAR_H - 8, Component.translatable("hud.townstead.rename"));
        renameBox.setMaxLength(CityManager.MAX_NAME_LENGTH);
        renameBox.setValue(initial);
        // Select the whole name so typing replaces it: click, type, Enter.
        renameBox.moveCursorToEnd(false);
        renameBox.setHighlightPos(0);
        addRenderableWidget(renameBox);
        setInitialFocus(renameBox);
        PlannerClient.CAMERA.releaseAll();
    }

    private void submitRename() {
        if (renameBox == null) return;
        String value = renameBox.getValue();
        PlannerClient.city().ifPresent(c -> {
            if (!value.isBlank() && !value.equals(c.name())) {
                ClientPacketDistributor.sendToServer(new RenameTownPayload(c.id(), value));
            }
        });
        closeRename();
    }

    private void closeRename() {
        if (renameBox == null) return;
        removeWidget(renameBox);
        renameBox = null;
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

    /** Optional Cities: Skylines-style edge scrolling (off by default: easy to trigger by accident in a window). */
    private void edgeScroll(int mouseX, int mouseY) {
        PlannerCamera cam = PlannerClient.CAMERA;
        boolean on = TownsteadConfig.EDGE_SCROLL.get() && renameBox == null;
        cam.edgeLeft = on && mouseX <= 1;
        cam.edgeRight = on && mouseX >= width - 2;
        cam.edgeForward = on && mouseY <= 1;
        cam.edgeBack = on && mouseY >= height - 2;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        updateHover(mouseX, mouseY);
        edgeScroll(mouseX, mouseY);
        float open = Mth.clamp((Util.getMillis() - openedAt) / 220f, 0f, 1f);
        float slide = 1f - (1f - open) * (1f - open); // ease-out
        CitySummary city = PlannerClient.city().orElse(null);

        g.pose().pushMatrix();
        g.pose().translate(0, (slide - 1f) * TOP_BAR_H);
        drawTopBar(g, city, mouseX, mouseY);
        g.pose().popMatrix();

        int barW = TOOLS.size() * SLOT + TOOLBAR_PAD * 2;
        int barX = (width - barW) / 2, barY = height - SLOT - TOOLBAR_PAD * 2 - 4;
        g.pose().pushMatrix();
        g.pose().translate(0, (1f - slide) * (SLOT + 16));
        drawToolbar(g, barX, barY, barW, mouseX, mouseY);
        g.pose().popMatrix();

        drawCursorInfo(g, city);
        drawFlash(g, barY);
        drawHints(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawTopBar(GuiGraphicsExtractor g, @Nullable CitySummary city, int mouseX, int mouseY) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, -4, -4, width + 8, TOP_BAR_H + 4);
        if (city == null) return;
        int y = (TOP_BAR_H - font.lineHeight) / 2;
        g.item(new ItemStack(Items.BELL), 6, 3);
        if (renameBox == null) {
            boolean hot = overName(mouseX, mouseY);
            g.text(font, Component.literal(city.name()), NAME_X, y, hot ? 0xFFFFFFFF : 0xFFFFE9B0, true);
            // Pencil-ish quill marks the name as editable.
            g.item(new ItemStack(Items.WRITABLE_BOOK), NAME_X + font.width(city.name()) + 3, 3);
            // Anchor the tooltip under the bar so it never covers the name it describes.
            if (hot) g.setTooltipForNextFrame(font, Component.translatable("hud.townstead.rename_hint"), mouseX, TOP_BAR_H + 14);
        }
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

    private void drawCursorInfo(GuiGraphicsExtractor g, @Nullable CitySummary city) {
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

    private void drawFlash(GuiGraphicsExtractor g, int toolbarY) {
        if (flash == null) return;
        long age = Util.getMillis() - flashAt;
        if (age > FLASH_MILLIS) { flash = null; return; }
        float alpha = age < FLASH_MILLIS - 400 ? 1f : (FLASH_MILLIS - age) / 400f;
        g.centeredText(font, flash, width / 2, toolbarY - font.lineHeight - 4, ARGB.color(Math.round(alpha * 255), 255, 214, 120));
    }

    /** One-line reminder that fades after a few seconds; H toggles the full control list. */
    private void drawHints(GuiGraphicsExtractor g) {
        if (showHelp) {
            List<Component> lines = List.of(
                    Component.translatable("help.townstead.pan"), Component.translatable("help.townstead.rotate"),
                    Component.translatable("help.townstead.zoom"), Component.translatable("help.townstead.tilt"),
                    Component.translatable("help.townstead.select"), Component.translatable("help.townstead.cancel"),
                    Component.translatable("help.townstead.rename"), Component.translatable("help.townstead.exit"));
            int w = lines.stream().mapToInt(font::width).max().orElse(100) + 14;
            int h = lines.size() * (font.lineHeight + 2) + 12;
            int x = width - w - 6, y = TOP_BAR_H + 6;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, w, h);
            for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x + 7, y + 7 + i * (font.lineHeight + 2), 0xFFF2F2F2, true);
            return;
        }
        long age = Util.getMillis() - openedAt;
        float alpha = age < HINT_MILLIS ? 1f : Math.max(0.35f, 1f - (age - HINT_MILLIS) / 1500f);
        Component hint = Component.translatable("hud.townstead.planner_hint");
        g.text(font, hint, width - font.width(hint) - 6, TOP_BAR_H + 6, ARGB.color(Math.round(alpha * 200), 255, 255, 255), true);
    }

    private int toolAt(double mouseX, double mouseY) {
        int barW = TOOLS.size() * SLOT + TOOLBAR_PAD * 2;
        int barX = (width - barW) / 2, barY = height - SLOT - TOOLBAR_PAD * 2 - 4;
        if (mouseY < barY + TOOLBAR_PAD || mouseY >= barY + TOOLBAR_PAD + SLOT) return -1;
        int i = (int) Math.floor((mouseX - barX - TOOLBAR_PAD) / SLOT);
        return i >= 0 && i < TOOLS.size() ? i : -1;
    }
}

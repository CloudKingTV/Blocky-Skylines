package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.client.TownsteadKeys;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.client.ClientRoads;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.BuildingGenerator;
import com.cloudkingtv.townstead.building.Demand;
import com.cloudkingtv.townstead.client.ClientLand;
import com.cloudkingtv.townstead.network.BuildRoadPayload;
import com.cloudkingtv.townstead.network.LandEditPayloads;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import com.cloudkingtv.townstead.building.TownGrowth;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.network.BulldozeRoadPayload;
import com.cloudkingtv.townstead.network.HistoryPayload;
import com.cloudkingtv.townstead.network.RenameTownPayload;
import com.cloudkingtv.townstead.road.Material;
import com.cloudkingtv.townstead.road.RoadCurve;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadPlan;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadType;
import java.util.ArrayList;
import java.util.Map;
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
 * tilt, middle-drag orbit, right-click or Esc cancel, B bulldoze. M leaves the planner. With the road tool:
 * 1/2/3 pick the road size, Tab switches straight/curved, hold Ctrl to place without snapping.
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
            new Tool("roads", new ItemStack(Items.DIRT_PATH), true),
            new Tool("zoning", new ItemStack(Items.OAK_SIGN), true),
            new Tool("utilities", new ItemStack(Items.REDSTONE_TORCH), true),
            new Tool("services", new ItemStack(Items.BELL), true),
            new Tool("transport", new ItemStack(Items.MINECART), false),
            new Tool("parks", new ItemStack(Items.OAK_SAPLING), true),
            new Tool("landmarks", new ItemStack(Items.BEACON), true),
            new Tool("land", new ItemStack(Items.FILLED_MAP), true),
            new Tool("info", new ItemStack(Items.COMPASS), true),
            new Tool("bulldoze", new ItemStack(Items.IRON_SHOVEL), true));
    private static final int INSPECT = 0, ROADS = 1, ZONING = 2, UTILITIES = 3, SERVICES = 4, PARKS = 6, LANDMARKS = 7, LAND = 8, INFO = 9, BULLDOZE = 10;

    /** What the utilities / services / parks tools can place. */
    private static List<ServiceType> placeList(int tool) {
        return switch (tool) {
            case UTILITIES -> ServiceType.UTILITIES;
            case SERVICES -> ServiceType.SERVICES.stream().filter(t -> t != ServiceType.PARK).toList();
            case PARKS -> List.of(ServiceType.PARK);
            case LANDMARKS -> ServiceType.LANDMARKS;
            default -> List.of();
        };
    }

    /** Whether the town hasn't reached the milestone that unlocks this road type, zone or service yet. */
    static boolean locked(Object thing) {
        return PlannerClient.city().map(c -> !com.cloudkingtv.townstead.progress.Milestone.unlocked(c.milestone(), thing)).orElse(false);
    }

    static Component lockedLine(Object thing) {
        var m = com.cloudkingtv.townstead.progress.Milestone.unlocking(thing);
        return Component.translatable("milestone.townstead.unlocks_at", m.displayName(), m.population);
    }

    /** The road type, zone or service behind a sub-panel button (null for modes and the clear brush). */
    private static @Nullable Object subThing(int i) {
        if (selectedTool == ROADS) return i < RoadType.values().length ? RoadType.values()[i] : null;
        if (selectedTool == ZONING) return i < ZoneType.PAINTABLE.size() ? ZoneType.PAINTABLE.get(i) : null;
        List<ServiceType> list = placeList(selectedTool);
        return i < list.size() ? list.get(i) : null;
    }

    private static boolean placing() {
        return !placeList(selectedTool).isEmpty();
    }

    /** Whether the info tool is out (the world renderer colours buildings then). */
    public static boolean infoActive() {
        return selectedTool == INFO;
    }

    /** Whether the land tool is out (the world renderer outlines tiles for sale then). */
    public static boolean landActive() {
        return selectedTool == LAND;
    }

    /** Whether a placing tool is out (the world renderer shows the lot ghost and coverage then). */
    public static boolean placingActive() {
        return placing();
    }
    /** Tool sub-panels: roads (three sizes, gap, two modes), zoning (three zones, gap, clear). */
    private static final int SUB_GAP = 6;
    private static final List<ItemStack> MODE_ICONS = List.of(new ItemStack(Items.RAIL), new ItemStack(Items.POWERED_RAIL));

    private final long openedAt = Util.getMillis();
    private final float[] hover = new float[TOOLS.size()];
    private static int selectedTool = INSPECT;
    /** Status icons over buildings (V toggles them, for a clear view of the town). */
    private static boolean showIcons = true;
    private boolean orbiting, showHelp, ctrlHeld;
    private boolean flashOk = true;
    private boolean painting;
    /** The building whose panel is open, or -1; the panel's scroll offset in lines. */
    private static int panelBuilding = -1;
    private int panelScroll;
    private static final int PANEL_W = 196;
    private long strokeSentAt;
    private @Nullable EditBox renameBox;
    private @Nullable Component flash;
    private long flashAt;

    /** Whether the zoning tool is out (the world renderer shows zonable lots then). */
    public static boolean zoningActive() {
        return selectedTool == ZONING;
    }

    public static boolean bulldozing() {
        return selectedTool == BULLDOZE;
    }

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
        if (key == InputConstants.KEY_LCONTROL || key == InputConstants.KEY_RCONTROL) ctrlHeld = true;
        // Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z): undo and redo, checked before Z's zoom.
        if (ctrlHeld && (key == InputConstants.KEY_Z || key == InputConstants.KEY_Y)) {
            boolean redo = key == InputConstants.KEY_Y || PlannerClient.CAMERA.fast;
            PlannerClient.city().ifPresent(c -> ClientPacketDistributor.sendToServer(new HistoryPayload(c.id(), redo)));
            click(redo ? 1.2f : 0.9f);
            return true;
        }
        if (TownsteadKeys.PLANNER.matches(event)) { PlannerClient.requestExit(); return true; }
        if (key == InputConstants.KEY_ESCAPE) { cancelOrExit(); return true; }
        if (key == InputConstants.KEY_B) { pickTool(selectedTool == BULLDOZE ? INSPECT : BULLDOZE); return true; }
        if (key == InputConstants.KEY_H) { showHelp = !showHelp; return true; }
        if (key == InputConstants.KEY_V) { showIcons = !showIcons; click(showIcons ? 1.2f : 0.8f); return true; }
        if (key == InputConstants.KEY_U) { MilestonePanel.open = !MilestonePanel.open; BudgetPanel.open = false; click(MilestonePanel.open ? 1.2f : 0.8f); return true; }
        if (key == InputConstants.KEY_L) { MilestonePanel.open = false; }
        if (key == InputConstants.KEY_L) { BudgetPanel.open = !BudgetPanel.open; click(BudgetPanel.open ? 1.2f : 0.8f); return true; }
        if ((placing() || selectedTool == INFO) && key >= InputConstants.KEY_1 && key < InputConstants.KEY_1 + Math.min(9, subCount())) {
            pickSub(key - InputConstants.KEY_1);
            return true;
        }
        if (selectedTool == ZONING && key >= InputConstants.KEY_1 && key <= InputConstants.KEY_1 + PlannerClient.CLEAR_BRUSH) {
            pickSub(key - InputConstants.KEY_1);
            return true;
        }
        if (selectedTool == ROADS) {
            RoadTool roads = PlannerClient.ROADS;
            if (key >= InputConstants.KEY_1 && key < InputConstants.KEY_1 + RoadType.values().length) {
                pickSub(key - InputConstants.KEY_1);
                return true;
            }
            if (key == InputConstants.KEY_TAB) {
                roads.mode = roads.mode == RoadTool.Mode.STRAIGHT ? RoadTool.Mode.CURVE : RoadTool.Mode.STRAIGHT;
                roads.control = null;
                click(1.1f);
                return true;
            }
        }
        return setHeld(key, true) || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (event.key() == InputConstants.KEY_LCONTROL || event.key() == InputConstants.KEY_RCONTROL) ctrlHeld = false;
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

    /** Esc / right-click: drop the road being drawn or the selection, then the tool, and only then leave (like CS). */
    private void cancelOrExit() {
        if (BudgetPanel.open || MilestonePanel.open) {
            BudgetPanel.open = false;
            MilestonePanel.open = false;
            click(0.8f);
        } else if (panelBuilding >= 0) {
            panelBuilding = -1;
            click(0.8f);
        } else if (selectedTool == ROADS && PlannerClient.ROADS.cancel()) {
            click(0.8f);
        } else if (painting) {
            painting = false;
            PlannerClient.STROKE.clear();
            click(0.8f);
        } else if (PlannerClient.selectedCell != null) {
            PlannerClient.selectedCell = null;
            click(0.8f);
        } else if (selectedTool != INSPECT) {
            pickTool(INSPECT);
        } else {
            PlannerClient.requestExit();
        }
    }

    // ---------------------------------------------------------------- mouse

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (BudgetPanel.scroll(x, y, scrollY)) return true;
        if (overPanel(x, y)) {
            panelScroll = Math.max(0, panelScroll - (int) Math.signum(scrollY) * 2);
            return true;
        }
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
        // Re-aim at the click itself: the cursor may have moved since the last frame drew.
        updateHover((int) event.x(), (int) event.y());
        if (BudgetPanel.click(event.x(), event.y())) return true;
        if (MilestonePanel.contains(event.x(), event.y())) return true;
        for (Object[] hit : chirpHits) {
            int[] r = (int[]) hit[0];
            if (event.x() >= r[0] && event.x() < r[2] && event.y() >= r[1] && event.y() < r[3]) {
                BlockPos at = (BlockPos) hit[1];
                PlannerClient.CAMERA.reset(at.getX() + 0.5, at.getZ() + 0.5, PlannerClient.CAMERA.yaw());
                click(1.3f);
                return true;
            }
        }
        if (overTreasury(event.x(), event.y())) { BudgetPanel.open = !BudgetPanel.open; click(1.2f); return true; }

        if (overPanel(event.x(), event.y())) {
            // The close cross sits in the panel's top-right corner.
            if (event.x() >= PANEL_W + 6 - 14 && event.y() < TOP_BAR_H + 4 + 14) { panelBuilding = -1; click(0.8f); }
            return true;
        }
        int tool = toolAt(event.x(), event.y());
        if (tool >= 0) { pickTool(tool); return true; }
        int sub = subButtonAt(event.x(), event.y());
        if (sub >= 0) { pickSub(sub); return true; }
        if (overName(event.x(), event.y())) {
            PlannerClient.city().ifPresent(c -> openRename(c.name()));
            click(1.0f);
            return true;
        }
        if (selectedTool == ROADS) { roadClick(); return true; }
        if (selectedTool == BULLDOZE) { bulldozeClick(); return true; }
        if (selectedTool == LAND) { landClick(); return true; }
        if (placing()) { placeClick(); return true; }
        if (selectedTool == ZONING) {
            painting = true;
            PlannerClient.STROKE.clear();
            strokeAdd();
            return true;
        }
        Building clicked = PlannerClient.hoveredBuilding();
        if (clicked != null) {
            panelBuilding = clicked.id;
            panelScroll = 0;
            PlannerClient.selectedCell = null;
            click(1.2f);
            return true;
        }
        if (PlannerClient.hoverCell != null) {
            PlannerClient.selectedCell = PlannerClient.hoverCell;
            panelBuilding = -1;
            click(1.2f);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) orbiting = false;
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && painting) {
            painting = false;
            if (!PlannerClient.STROKE.isEmpty()) {
                int zone = PlannerClient.zoneBrush == PlannerClient.CLEAR_BRUSH ? -1 : PlannerClient.zoneBrush;
                PlannerClient.city().ifPresent(c -> ClientPacketDistributor.sendToServer(
                        new LandEditPayloads.Paint(c.id(), zone, new ArrayList<>(PlannerClient.STROKE))));
                click(1.2f);
            }
            // Keep the stroke drawn until the server's answer replaces it.
            strokeSentAt = Util.getMillis();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (orbiting) {
            PlannerClient.CAMERA.rotate((float) dx * 0.45f);
            PlannerClient.CAMERA.tilt((float) dy * 0.3f);
            return true;
        }
        if (painting) {
            updateHover((int) event.x(), (int) event.y());
            strokeAdd();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public void removed() {
        PlannerClient.CAMERA.releaseAll();
        super.removed();
    }

    private void roadClick() {
        RoadTool roads = PlannerClient.ROADS;
        CitySummary city = PlannerClient.city().orElse(null);
        if (city == null || roads.cursor == null) return;
        boolean finishing = roads.start != null && (roads.mode == RoadTool.Mode.STRAIGHT || roads.control != null);
        RoadTool.Preview preview = roads.preview(city);
        if (finishing && preview != null && !preview.buildable()) {
            flash(problem(preview), false);
            click(0.5f);
            return;
        }
        RoadCurve curve = roads.click();
        if (curve != null) {
            ClientPacketDistributor.sendToServer(new BuildRoadPayload(city.id(), roads.type, curve));
            click(1.3f);
        } else {
            click(1.15f);
        }
    }

    private void bulldozeClick() {
        RoadSegment seg = PlannerClient.hoveredSegment();
        Building building = PlannerClient.hoveredBuilding();
        PlannerClient.city().ifPresent(city -> {
            if (building != null) ClientPacketDistributor.sendToServer(new LandEditPayloads.BulldozeBuilding(city.id(), building.id));
            else if (seg != null) ClientPacketDistributor.sendToServer(new BulldozeRoadPayload(city.id(), seg.id()));
            else return;
            click(0.7f);
        });
    }

    /** Adds the hovered cell to the brush stroke when it can take the zone (any cell can be cleared). */
    private void strokeAdd() {
        BlockPos cell = PlannerClient.hoverCell;
        if (cell == null) return;
        int cx = Math.floorDiv(cell.getX(), TilePos.CELL), cz = Math.floorDiv(cell.getZ(), TilePos.CELL);
        boolean clearing = PlannerClient.zoneBrush == PlannerClient.CLEAR_BRUSH;
        if (!clearing && PlannerClient.zonable(cx, cz) == null) return;
        if (PlannerClient.STROKE.add(ZoneRules.cellKey(cx, cz)) && PlannerClient.STROKE.size() % 2 == 1) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.15f));
        }
    }

    private static Component problem(RoadTool.Preview preview) {
        if (!preview.plan().ok()) return Component.translatable(preview.plan().issues.iterator().next().key());
        return Component.translatable("road.townstead.missing", missingList(preview.missing()));
    }

    private static Component missingList(Map<Material, Integer> missing) {
        var list = Component.empty();
        boolean first = true;
        for (var e : missing.entrySet()) {
            if (!first) list.append(", ");
            list.append(Component.literal(e.getValue() + " ")).append(e.getKey().displayName());
            first = false;
        }
        return list;
    }

    /** Why the hovered tile can't be bought, or null. */
    public static @Nullable String landProblem(CitySummary city, TilePos tile) {
        return com.cloudkingtv.townstead.city.TownActions.buyProblem(java.util.Set.copyOf(city.tiles()), city.milestone(), city.treasury(), tile,
                t -> PlannerClient.tileBlocked(city, t));
    }

    private void landClick() {
        TilePos tile = PlannerClient.hoverTile;
        CitySummary city = PlannerClient.city().orElse(null);
        if (tile == null || city == null) return;
        String problem = landProblem(city, tile);
        if (problem != null) { flash(Component.translatable(problem)); click(0.5f); return; }
        ClientPacketDistributor.sendToServer(new LandEditPayloads.BuyTile(city.id(), tile.x(), tile.z()));
        click(1.3f);
    }

    private void drawLandInfo(GuiGraphicsExtractor g, @Nullable CitySummary city, int mouseX, int mouseY) {
        if (city == null) return;
        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        int allowed = com.cloudkingtv.townstead.progress.Milestone.tileAllowance(city.milestone());
        lines.add(Component.translatable("land.townstead.title", city.tiles().size(), allowed));
        colors.add(0xFFFFE9B0);
        TilePos tile = PlannerClient.hoverTile;
        if (tile != null) {
            String problem = landProblem(city, tile);
            long price = com.cloudkingtv.townstead.city.TownActions.tilePrice(city.tiles().size());
            lines.add(Component.translatable("land.townstead.tile", tile.x(), tile.z()));
            colors.add(0xFFDDDDDD);
            if (problem == null) {
                lines.add(Component.translatable("land.townstead.price", price));
                colors.add(0xFF9EE37D);
                lines.add(Component.translatable("land.townstead.click"));
                colors.add(0xFFB8B8B8);
            } else {
                lines.add(Component.translatable(problem));
                colors.add(problem.equals("land.townstead.owned") ? 0xFF9EE37D : 0xFFFF9A7A);
                if (problem.equals("land.townstead.need_milestone")) {
                    var next = com.cloudkingtv.townstead.progress.Milestone.of(city.milestone()).next();
                    if (next != null) {
                        lines.add(Component.translatable("land.townstead.next", next.displayName(), next.population, next.tiles));
                        colors.add(0xFFB8B8B8);
                    }
                } else if (problem.equals("land.townstead.too_poor")) {
                    lines.add(Component.translatable("land.townstead.price", price));
                    colors.add(0xFFB8B8B8);
                }
            }
        }
        cursorPanel(g, lines, colors, mouseX, mouseY);
    }

    private void placeClick() {
        BlockPos cell = PlannerClient.hoverCell;
        CitySummary city = PlannerClient.city().orElse(null);
        if (cell == null || city == null) return;
        int cx = Math.floorDiv(cell.getX(), TilePos.CELL), cz = Math.floorDiv(cell.getZ(), TilePos.CELL);
        Component problem = placeProblem(cx, cz);
        if (problem != null) { flash(problem, false); click(0.5f); return; }
        ClientPacketDistributor.sendToServer(new LandEditPayloads.PlaceService(city.id(), PlannerClient.placing.ordinal(), ZoneRules.cellKey(cx, cz)));
        click(1.3f);
    }

    /** Why the placing tool's service can't go on this lot, or null if it can. Mirrors the server's checks. */
    public static @Nullable Component placeProblem(int cx, int cz) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (ClientLand.get().buildingAt(ZoneRules.cellKey(cx, cz)) != null) return Component.translatable("service.townstead.lot_taken");
        if (PlannerClient.frontage(cx, cz) == null) return Component.translatable("zone.townstead.no_frontage");
        if (locked(PlannerClient.placing)) return lockedLine(PlannerClient.placing);
        var city = PlannerClient.city().orElse(null);
        if (city != null && PlannerClient.placing.landmark()
                && ClientLand.get().buildingsOf(city.id()).stream().anyMatch(o -> PlannerClient.placing.key().equals(o.service))) {
            return Component.translatable("service.townstead.only_one");
        }
        if (city != null && PlannerClient.lotFor(city, PlannerClient.placing, cx, cz) == null) {
            return Component.translatable("service.townstead.no_room", PlannerClient.placing.wide, PlannerClient.placing.deep);
        }
        if (PlannerClient.placing == ServiceType.WATERMILL && minecraft.level != null && !TownGrowth.waterNear(minecraft.level, cx, cz)) {
            return Component.translatable("service.townstead.needs_water");
        }
        return null;
    }

    private void pickSub(int i) {
        Object thing = subThing(i);
        if (thing != null && locked(thing)) {
            flash(Component.translatable("milestone.townstead.locked_short", lockedLine(thing)));
            click(0.6f);
            return;
        }
        if (placing()) {
            List<ServiceType> list = placeList(selectedTool);
            if (i < list.size()) PlannerClient.placing = list.get(i);
            click(1.1f);
            return;
        }
        if (selectedTool == INFO) {
            PlannerClient.infoView = i;
            click(1.1f);
            return;
        }
        if (selectedTool == ZONING) {
            PlannerClient.zoneBrush = i;
            click(1.1f);
            return;
        }
        RoadTool roads = PlannerClient.ROADS;
        int types = RoadType.values().length;
        if (i < types) roads.type = RoadType.values()[i];
        else {
            roads.mode = RoadTool.Mode.values()[i - types];
            roads.control = null;
        }
        click(1.1f);
    }

    private void pickTool(int tool) {
        if (TOOLS.get(tool).available()) {
            if (tool != selectedTool) PlannerClient.ROADS.reset();
            selectedTool = tool;
            if (placing() && !placeList(tool).contains(PlannerClient.placing)) PlannerClient.placing = placeList(tool).getFirst();
            PlannerClient.selectedCell = null;
            click(1.0f);
        } else {
            flash(Component.translatable("tool.townstead.locked_named", Component.translatable("tool.townstead." + TOOLS.get(tool).key())));
            click(0.6f);
        }
    }

    private void flash(Component message) {
        flash(message, false);
    }

    /** A one-line message above the toolbar: gold for problems, green for done. */
    public void flash(Component message, boolean ok) {
        flash = message;
        flashOk = ok;
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
        PlannerClient.hoverTile = null;
        PlannerClient.hoveredSegmentId = -1;
        PlannerClient.hoveredBuildingId = -1;
        if (mouseY < TOP_BAR_H || toolAt(mouseX, mouseY) >= 0 || subButtonAt(mouseX, mouseY) >= 0 || overPanel(mouseX, mouseY)
                || BudgetPanel.contains(mouseX, mouseY) || MilestonePanel.contains(mouseX, mouseY) || overChirp(mouseX, mouseY)
                || minecraft.level == null) {
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
        Vec3 at = hit.getLocation();
        if (selectedTool == ROADS) {
            PlannerClient.hoverCell = null;
            PlannerClient.ROADS.hover(at.x, at.z, ctrlHeld);
            return;
        }
        if (selectedTool == LAND) {
            PlannerClient.hoverCell = null;
            PlannerClient.hoverTile = TilePos.ofBlock((int) Math.floor(at.x), (int) Math.floor(at.z));
            return;
        }
        // Buildings first: the cell under the cursor (inspect and bulldoze pick them out).
        BlockPos hp = hit.getBlockPos();
        Building b = ClientLand.get().buildingAt(ZoneRules.cellKey(Math.floorDiv(hp.getX(), TilePos.CELL), Math.floorDiv(hp.getZ(), TilePos.CELL)));
        boolean ours = b != null && PlannerClient.city().map(c -> c.id().equals(b.city)).orElse(false);
        if (ours && (selectedTool == BULLDOZE || selectedTool == INSPECT || selectedTool == INFO)) PlannerClient.hoveredBuildingId = b.id;
        if (selectedTool == BULLDOZE) {
            PlannerClient.hoverCell = null;
            if (ours) return;
            PlannerClient.hoveredSegmentId = ClientRoads.network().nearestSegment(at.x, at.z, 0.5)
                    .filter(h -> PlannerClient.city().map(c -> c.id().equals(h.segment().city())).orElse(false))
                    .map(h -> h.segment().id()).orElse(-1);
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
        // Status icons sit in the world, under every panel and overlay.
        drawStatusIcons(g, city, mouseX, mouseY);

        g.pose().pushMatrix();
        g.pose().translate(0, (slide - 1f) * TOP_BAR_H);
        drawTopBar(g, city, mouseX, mouseY);
        g.pose().popMatrix();

        int barW = TOOLS.size() * SLOT + TOOLBAR_PAD * 2;
        int barX = (width - barW) / 2, barY = height - SLOT - TOOLBAR_PAD * 2 - 4;
        g.pose().pushMatrix();
        g.pose().translate(0, (1f - slide) * (SLOT + 16));
        drawToolbar(g, barX, barY, barW, mouseX, mouseY);
        if (subCount() > 0) drawSubPanel(g, mouseX, mouseY);
        g.pose().popMatrix();

        if (!painting && strokeSentAt > 0 && Util.getMillis() - strokeSentAt > 1500) { PlannerClient.STROKE.clear(); strokeSentAt = 0; }
        drawBanner(g, city);
        if (banner == null || BudgetPanel.open || MilestonePanel.open) drawChirps(g, mouseX, mouseY);
        else chirpHits.clear();
        Building hovered = PlannerClient.hoveredBuilding();
        // The bars and their buttons have their own tooltips: no cursor readout over them.
        boolean overBars = mouseY < TOP_BAR_H || toolAt(mouseX, mouseY) >= 0 || subButtonAt(mouseX, mouseY) >= 0;
        if (selectedTool == INFO) drawInfoLegend(g, city, hovered, mouseX, mouseY);
        else if (!overBars) {
            if (selectedTool == ROADS) drawRoadInfo(g, city, mouseX, mouseY);
            else if (selectedTool == BULLDOZE) drawBulldozeInfo(g, city, mouseX, mouseY);
            else if (selectedTool == ZONING) drawZoningInfo(g, city, mouseX, mouseY);
            else if (selectedTool == LAND) drawLandInfo(g, city, mouseX, mouseY);
            else if (placing()) drawPlaceInfo(g, city, mouseX, mouseY);
            else if (hovered != null) drawBuildingInfo(g, hovered, mouseX, mouseY);
            else drawCursorInfo(g, city);
        }
        drawFlash(g, subCount() > 0 ? subY() : barY);
        if (!BudgetPanel.open && !MilestonePanel.open) drawHints(g);
        drawPanel(g, mouseX, mouseY);
        BudgetPanel.draw(g, font, width, height, TOP_BAR_H, barY, mouseX, mouseY);
        MilestonePanel.draw(g, font, width, TOP_BAR_H, barY, mouseX, mouseY);
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
            var reached = com.cloudkingtv.townstead.progress.Milestone.of(city.milestone());
            var next = reached.next();
            var model = PlannerClient.model();
            int people = model == null ? 0 : model.demand.population();
            int barW = Math.max(60, font.width(city.name()));
            g.fill(NAME_X, TOP_BAR_H - 4, NAME_X + barW, TOP_BAR_H - 2, 0x80202020);
            if (next != null) {
                int prev = reached.population;
                int fill = (int) Math.max(0, Math.min(barW, (long) barW * (people - prev) / Math.max(1, next.population - prev)));
                g.fill(NAME_X, TOP_BAR_H - 4, NAME_X + fill, TOP_BAR_H - 2, 0xFF9EE37D);
            } else g.fill(NAME_X, TOP_BAR_H - 4, NAME_X + barW, TOP_BAR_H - 2, 0xFFFFD36B);
            if (hot) g.setTooltipForNextFrame(font, List.of(Component.translatable("hud.townstead.rename_hint").getVisualOrderText(),
                    (next == null ? Component.translatable("milestone.townstead.top", reached.displayName())
                            : Component.translatable("milestone.townstead.progress", reached.displayName(), people, next.population, next.displayName()))
                            .getVisualOrderText(),
                    Component.translatable("hud.townstead.tiles", city.tiles().size()).getVisualOrderText()), mouseX, TOP_BAR_H + 14);
        }
        int x = width - 8;
        int stored = city.stock().values().stream().mapToInt(Integer::intValue).sum();
        int stockRight = x;
        x = rightStat(g, x, y, new ItemStack(Items.BARREL), Component.literal(String.valueOf(stored)));
        if (renameBox == null && mouseY < TOP_BAR_H && mouseX > x + 20 && mouseX <= stockRight) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("hud.townstead.stock"));
            for (Material m : Material.values()) {
                int n = Stock.count(city.stock(), m);
                if (n > 0) lines.add(Component.translatable("hud.townstead.stock_line", m.displayName(), n));
            }
            if (lines.size() == 1) lines.add(Component.translatable("hud.townstead.stock_empty"));
            g.setTooltipForNextFrame(font, lines.stream().map(Component::getVisualOrderText).toList(), mouseX, TOP_BAR_H + 14);
        }
        var model = PlannerClient.model();
        Demand demand = model != null ? model.demand : Demand.of(ClientLand.get().buildingsOf(city.id()));
        x = rightStat(g, x, y, new ItemStack(Items.VILLAGER_SPAWN_EGG), Component.translatable("hud.townstead.population", demand.population()));
        if (model != null) {
            int happy = model.townHappiness();
            int happyRight = x;
            x = rightStat(g, x, y, new ItemStack(happy >= 50 ? Items.POPPY : Items.WITHER_ROSE), Component.literal(happy + "%"));
            if (renameBox == null && mouseY < TOP_BAR_H && mouseX > x + 20 && mouseX <= happyRight) {
                g.setTooltipForNextFrame(font, Component.translatable("hud.townstead.happiness", happy, model.unemploymentRate()), mouseX, TOP_BAR_H + 14);
            }
        }
        long net = model == null ? 0 : com.cloudkingtv.townstead.economy.Finance.daily(model).net();
        int moneyRight = x;
        Component netText = Component.literal(" " + com.cloudkingtv.townstead.economy.Budget.signed(net))
                .withColor(net >= 0 ? 0xFF9EE37D : 0xFFFF8A7A);
        x = rightStat(g, x, y, new ItemStack(Items.EMERALD), Component.literal(com.cloudkingtv.townstead.economy.Budget.format(city.treasury()))
                .withColor(city.treasury() < 0 ? 0xFFFF8A7A : 0xFFF2F2F2).append(netText));
        treasuryX0 = x + 20;
        treasuryX1 = moneyRight;
        if (renameBox == null && overTreasury(mouseX, mouseY)) {
            g.setTooltipForNextFrame(font, Component.translatable("hud.townstead.treasury_tip"), mouseX, TOP_BAR_H + 14);
        }
        drawDemand(g, x - 4, demand, mouseX, mouseY);
    }

    /** Click areas of the chirps drawn this frame: {int[] x0, y0, x1, y1}, BlockPos. */
    private final List<Object[]> chirpHits = new ArrayList<>();

    private boolean overChirp(double x, double y) {
        for (Object[] hit : chirpHits) {
            int[] r = (int[]) hit[0];
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return true;
        }
        return false;
    }

    /**
     * The latest chirps, top right, fading after fifteen seconds. With Inspect or Info a click flies the camera there;
     * with an editing tool they let clicks through and fade under the cursor, so they never get in the way of a build.
     */
    private void drawChirps(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        chirpHits.clear();
        if (BudgetPanel.open || MilestonePanel.open || showHelp) return;
        var all = com.cloudkingtv.townstead.client.ClientChirps.all();
        boolean clickable = selectedTool == INSPECT || selectedTool == INFO;
        long now = Util.getMillis();
        int w = 172, x = width - w - 6, y = TOP_BAR_H + 18, shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < 3; i--) {
            var e = all.get(i);
            long age = now - e.at();
            if (age > 15_000) break;
            float alpha = age > 12_000 ? (15_000 - age) / 3000f : Math.min(1f, age / 200f);
            var lines = font.split(e.chirp().text(), w - 10);
            if (lines.size() > 3) lines = lines.subList(0, 3);
            int h = 4 + font.lineHeight + 1 + lines.size() * (font.lineHeight + 1) + 2;
            boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            if (hot && !clickable) alpha *= 0.3f;
            g.fill(x, y, x + w, y + h, ARGB.color(Math.round(alpha * (hot ? 0xE0 : 0xB0)), 24, 22, 20));
            g.fill(x, y, x + 2, y + h, ARGB.color(Math.round(alpha * 255), 110, 200, 255));
            g.text(font, Component.literal("@" + e.chirp().author().replace(' ', '_')), x + 5, y + 3, ARGB.color(Math.round(alpha * 255), 120, 210, 255), true);
            for (int k = 0; k < lines.size(); k++) {
                g.text(font, lines.get(k), x + 5, y + 4 + font.lineHeight + 1 + k * (font.lineHeight + 1), ARGB.color(Math.round(alpha * 255), 236, 236, 236), true);
            }
            if (clickable) {
                chirpHits.add(new Object[] {new int[] {x, y, x + w, y + h}, e.chirp().at()});
                if (hot) g.setTooltipForNextFrame(font, Component.translatable("chirp.townstead.fly"), mouseX, mouseY);
            }
            y += h + 2;
            shown++;
        }
    }

    private int treasuryX0, treasuryX1;

    private boolean overTreasury(double x, double y) {
        return y < TOP_BAR_H && x >= treasuryX0 - 18 && x <= treasuryX1;
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
        int a = Math.round(alpha * 255);
        g.centeredText(font, flash, width / 2, toolbarY - font.lineHeight - 4, flashOk ? ARGB.color(a, 140, 230, 120) : ARGB.color(a, 255, 214, 120));
    }

    // ---------------------------------------------------------------- milestone banner

    private static final long BANNER_MILLIS = 6500;
    private static java.util.@Nullable UUID bannerCity;
    private static int seenMilestone = -1;
    private static long bannerAt;
    private static com.cloudkingtv.townstead.progress.@Nullable Milestone banner;

    /**
     * The title Minecraft shows on reaching a milestone is hidden under the planner, so the planner shows its own:
     * the new title, the reward and everything it unlocks. Also shown on opening the planner after one was reached
     * while it was closed.
     */
    private void drawBanner(GuiGraphicsExtractor g, @Nullable CitySummary city) {
        if (city == null) return;
        long now = Util.getMillis();
        if (!city.id().equals(bannerCity)) {
            bannerCity = city.id();
            seenMilestone = city.milestone();
        } else if (city.milestone() > seenMilestone) {
            seenMilestone = city.milestone();
            banner = com.cloudkingtv.townstead.progress.Milestone.of(city.milestone());
            bannerAt = now;
        }
        if (banner == null || BudgetPanel.open || MilestonePanel.open) return;
        long age = now - bannerAt;
        if (age > BANNER_MILLIS) { banner = null; return; }
        float alpha = Math.min(1f, age / 300f) * (age > BANNER_MILLIS - 1000 ? (BANNER_MILLIS - age) / 1000f : 1f);
        int a = Math.max(4, Math.round(alpha * 255));
        List<Component> things = new ArrayList<>();
        banner.roads.forEach(r -> things.add(r.displayName()));
        banner.zones.forEach(z -> things.add(z.displayName()));
        banner.services.forEach(sv -> things.add(sv.displayName()));
        net.minecraft.network.chat.MutableComponent list = Component.empty();
        for (int i = 0; i < things.size(); i++) { if (i > 0) list.append(", "); list.append(things.get(i)); }
        var unlockLines = things.isEmpty() ? List.<net.minecraft.util.FormattedCharSequence>of()
                : font.split(Component.translatable("milestone.townstead.banner_unlocks", list), 260);
        int w = 280, h = 16 + 20 + 12 + unlockLines.size() * (font.lineHeight + 1) + 10;
        int x = (width - w) / 2, y = TOP_BAR_H + 26;
        g.fill(x, y, x + w, y + h, ARGB.color(Math.round(alpha * 0xD0), 20, 18, 14));
        g.fill(x, y, x + w, y + 1, ARGB.color(a, 255, 210, 90));
        g.fill(x, y + h - 1, x + w, y + h, ARGB.color(a, 255, 210, 90));
        g.centeredText(font, Component.translatable("milestone.townstead.banner"), width / 2, y + 6, ARGB.color(a, 255, 210, 90));
        g.pose().pushMatrix();
        g.pose().translate(width / 2f, y + 18);
        g.pose().scale(2f, 2f);
        g.centeredText(font, banner.displayName(), 0, 0, ARGB.color(a, 255, 255, 255));
        g.pose().popMatrix();
        g.centeredText(font, Component.translatable("milestone.townstead.banner_reward", banner.reward, banner.tiles), width / 2, y + 38,
                ARGB.color(a, 140, 230, 120));
        for (int i = 0; i < unlockLines.size(); i++) {
            g.centeredText(font, unlockLines.get(i), width / 2, y + 50 + i * (font.lineHeight + 1), ARGB.color(a, 230, 230, 230));
        }
    }

    /** One-line reminder that fades after a few seconds; H toggles the full control list. */
    private void drawHints(GuiGraphicsExtractor g) {
        if (showHelp) {
            List<Component> lines = List.of(
                    Component.translatable("help.townstead.pan"), Component.translatable("help.townstead.rotate"),
                    Component.translatable("help.townstead.zoom"), Component.translatable("help.townstead.tilt"),
                    Component.translatable("help.townstead.select"), Component.translatable("help.townstead.roads"),
                    Component.translatable("help.townstead.roads_keys"), Component.translatable("help.townstead.zoning"),
                    Component.translatable("help.townstead.undo"), Component.translatable("help.townstead.budget"), Component.translatable("help.townstead.milestones"),
                    Component.translatable("help.townstead.icons"),
                    Component.translatable("help.townstead.cancel"),
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

    // ---------------------------------------------------------------- road tool UI

    private int subY() {
        return height - SLOT - TOOLBAR_PAD * 2 - 4 - SLOT - TOOLBAR_PAD * 2 - 4;
    }

    private int subCount() {
        return switch (selectedTool) {
            case ROADS -> RoadType.values().length + RoadTool.Mode.values().length;
            case ZONING -> PlannerClient.CLEAR_BRUSH + 1;
            case INFO -> PlannerClient.INFO_VIEWS.size();
            default -> placeList(selectedTool).size();
        };
    }

    /** Index of the first button after the panel's gap (none for the placing and info panels). */
    private int subGapAt() {
        return selectedTool == ROADS ? RoadType.values().length : selectedTool == ZONING ? PlannerClient.CLEAR_BRUSH : 99;
    }

    private int subX() {
        return (width - (subCount() * SLOT + SUB_GAP + TOOLBAR_PAD * 2)) / 2;
    }

    private int subButtonX(int i) {
        return subX() + TOOLBAR_PAD + i * SLOT + (i >= subGapAt() ? SUB_GAP : 0);
    }

    private int subButtonAt(double mouseX, double mouseY) {
        int n = subCount();
        if (n == 0) return -1;
        int y = subY() + TOOLBAR_PAD;
        if (mouseY < y || mouseY >= y + SLOT) return -1;
        for (int i = 0; i < n; i++) {
            int x = subButtonX(i);
            if (mouseX >= x && mouseX < x + SLOT) return i;
        }
        return -1;
    }

    private void drawSubPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int n = subCount();
        int x = subX(), y = subY(), w = n * SLOT + SUB_GAP + TOOLBAR_PAD * 2;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, w, SLOT + TOOLBAR_PAD * 2);
        int hovered = subButtonAt(mouseX, mouseY);
        boolean roads = selectedTool == ROADS;
        RoadTool tool = PlannerClient.ROADS;
        int types = RoadType.values().length;
        for (int i = 0; i < n; i++) {
            boolean on;
            ItemStack icon;
            if (roads) {
                on = i < types ? tool.type.ordinal() == i : tool.mode.ordinal() == i - types;
                icon = i < types ? RoadType.values()[i].icon() : MODE_ICONS.get(i - types);
            } else if (placing()) {
                ServiceType st = placeList(selectedTool).get(i);
                on = PlannerClient.placing == st;
                icon = st.icon();
            } else if (selectedTool == INFO) {
                on = PlannerClient.infoView == i;
                icon = infoIcon(PlannerClient.INFO_VIEWS.get(i));
            } else {
                on = PlannerClient.zoneBrush == i;
                icon = i < ZoneType.PAINTABLE.size() ? ZoneType.PAINTABLE.get(i).icon() : new ItemStack(Items.BRUSH);
            }
            int bx = subButtonX(i), by = y + TOOLBAR_PAD;
            Object thing = subThing(i);
            boolean isLocked = thing != null && locked(thing);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, on ? BUTTON_SELECTED : i == hovered ? BUTTON_HOVER : BUTTON, bx + 1, by + 1, SLOT - 2, SLOT - 2,
                    isLocked ? 0xFF8A8A8A : 0xFFFFFFFF);
            if (selectedTool == ZONING && i < ZoneType.PAINTABLE.size()) g.fill(bx + 3, by + SLOT - 5, bx + SLOT - 3, by + SLOT - 3, 0xFF000000 | ZoneType.PAINTABLE.get(i).rgb);
            g.item(icon, bx + 4, by + 4);
            if (isLocked) {
                g.fill(bx + 2, by + 2, bx + SLOT - 2, by + SLOT - 2, 0x99101010);
                g.text(font, "\u2716", bx + SLOT - 9, by + 2, 0xFFE0A060, true);
            }
            if ((!roads || i < types) && i < 9) g.text(font, String.valueOf(i + 1), bx + SLOT - 7, by + SLOT - 10, 0xFFFFFFFF, true);
        }
        if (hovered >= 0) {
            Component name;
            if (placing()) {
                ServiceType st = placeList(selectedTool).get(hovered);
                name = Component.translatable(st.kind == ServiceType.Kind.SERVICE ? "service.townstead.tip_service" : "service.townstead.tip_utility",
                        st.displayName(), st.kind == ServiceType.Kind.SERVICE ? st.radius : st.capacity);
            } else if (selectedTool == INFO) {
                name = Component.translatable("info.townstead." + PlannerClient.INFO_VIEWS.get(hovered));
            } else if (roads) {
                name = hovered < types ? Component.translatable("road.townstead.type_tip", RoadType.values()[hovered].displayName(),
                        RoadType.values()[hovered].width())
                        : Component.translatable("road.townstead.mode." + RoadTool.Mode.values()[hovered - types].name().toLowerCase(java.util.Locale.ROOT));
            } else {
                name = hovered < ZoneType.PAINTABLE.size() ? ZoneType.PAINTABLE.get(hovered).displayName() : Component.translatable("zone.townstead.clear");
            }
            Object thing = subThing(hovered);
            if (thing != null && locked(thing)) {
                g.setTooltipForNextFrame(font, List.of(name.getVisualOrderText(), lockedLine(thing).getVisualOrderText()), mouseX, mouseY);
            } else {
                g.setTooltipForNextFrame(font, name, mouseX, mouseY);
            }
        }
    }

    /** Cities: Skylines demand bars (R, C, I, O): how much the town wants each kind of building, with the reasons. */
    private void drawDemand(GuiGraphicsExtractor g, int right, Demand demand, int mouseX, int mouseY) {
        ZoneType[] bars = {ZoneType.RESIDENTIAL, ZoneType.COMMERCIAL, ZoneType.INDUSTRIAL, ZoneType.OFFICE};
        int barW = 5, gap = 3, h = TOP_BAR_H - 8, x0 = right - bars.length * (barW + gap) - 10;
        // The label only when it won't run into the town's name.
        int nameEnd = PlannerClient.city().map(c -> NAME_X + font.width(c.name()) + 20).orElse(NAME_X);
        if (x0 < nameEnd + 4) return;
        if (x0 - font.width("RCIO") - 3 > nameEnd) g.text(font, "RCIO", x0 - font.width("RCIO") - 3, (TOP_BAR_H - font.lineHeight) / 2, 0xFFB8B8B8, true);
        for (int i = 0; i < bars.length; i++) {
            int bx = x0 + i * (barW + gap), top = 4;
            g.fill(bx, top, bx + barW, top + h, 0x80101010);
            int fill = Math.round(h * demand.of(bars[i].need) / 100f);
            g.fill(bx, top + h - fill, bx + barW, top + h, 0xFF000000 | bars[i].rgb);
        }
        if (renameBox == null && mouseY < TOP_BAR_H && mouseX >= x0 - 28 && mouseX < x0 + bars.length * (barW + gap)) {
            List<Component> lines = List.of(Component.translatable("demand.townstead.title"),
                    Component.translatable("demand.townstead.residential", demand.residential(),
                            demand.shopJobs() + demand.workshopJobs() + demand.officeJobs(), demand.workers()),
                    Component.translatable("demand.townstead.commercial", demand.commercial(), demand.plannedPeople(), demand.shopJobs()),
                    Component.translatable("demand.townstead.industrial", demand.industrial(), demand.workshopJobs()),
                    Component.translatable("demand.townstead.office", demand.office(), demand.educated(), demand.officeJobs(), ZoneType.OFFICE.minPopulation),
                    Component.translatable("demand.townstead.taxes"),
                    Component.translatable("demand.townstead.density", ZoneType.RESIDENTIAL_HIGH.minPopulation),
                    Component.translatable("demand.townstead.hint"));
            g.setTooltipForNextFrame(font, lines.stream().map(Component::getVisualOrderText).toList(), mouseX, TOP_BAR_H + 14);
        }
    }

    // ---------------------------------------------------------------- building panel

    private boolean overPanel(double x, double y) {
        return panelBuilding >= 0 && ClientLand.get().building(panelBuilding) != null && x >= 6 && x < 6 + PANEL_W
                && y >= TOP_BAR_H + 4 && y < panelBottom();
    }

    private int panelBottom() {
        return (subCount() > 0 ? subY() : height - SLOT - TOOLBAR_PAD * 2 - 4) - 4;
    }

    private void drawPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (panelBuilding < 0) return;
        Building b = ClientLand.get().building(panelBuilding);
        if (b == null) { panelBuilding = -1; return; }
        // Wrap each line to the panel; continuation lines sit a little further in.
        record Row(net.minecraft.util.FormattedCharSequence text, int color, int indent) {}
        List<Row> lines = new ArrayList<>();
        for (BuildingPanel.Line line : BuildingPanel.lines(b)) {
            var parts = font.split(line.text(), PANEL_W - 16 - line.indent());
            if (parts.isEmpty()) lines.add(new Row(net.minecraft.util.FormattedCharSequence.EMPTY, line.color(), line.indent()));
            for (int i = 0; i < parts.size(); i++) lines.add(new Row(parts.get(i), line.color(), line.indent() + (i > 0 ? 6 : 0)));
        }
        int x = 6, y = TOP_BAR_H + 4, h = panelBottom() - y, lineH = font.lineHeight + 2;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, PANEL_W, h);
        int visible = (h - 12) / lineH;
        panelScroll = Math.min(panelScroll, Math.max(0, lines.size() - visible));
        g.enableScissor(x + 4, y + 4, x + PANEL_W - 4, y + h - 4);
        for (int i = panelScroll; i < lines.size() && i - panelScroll < visible; i++) {
            Row row = lines.get(i);
            g.text(font, row.text(), x + 7 + row.indent(), y + 6 + (i - panelScroll) * lineH, row.color(), true);
        }
        g.disableScissor();
        boolean hot = mouseX >= x + PANEL_W - 14 && mouseX < x + PANEL_W && mouseY >= y && mouseY < y + 14;
        g.text(font, "\u00d7", x + PANEL_W - 11, y + 4, hot ? 0xFFFFFFFF : 0xFFB0B0B0, true);
        if (lines.size() > visible) {
            int barH = Math.max(8, h * visible / lines.size()), barY = y + (h - barH) * panelScroll / Math.max(1, lines.size() - visible);
            g.fill(x + PANEL_W - 4, barY, x + PANEL_W - 2, barY + barH, 0x80FFFFFF);
        }
    }

    private static ItemStack infoIcon(String view) {
        return switch (view) {
            case "power" -> new ItemStack(Items.REDSTONE);
            case "water" -> new ItemStack(Items.WATER_BUCKET);
            case "level" -> new ItemStack(Items.EXPERIENCE_BOTTLE);
            case "happiness" -> new ItemStack(Items.POPPY);
            case "land_value" -> new ItemStack(Items.GOLD_INGOT);
            case "pollution" -> new ItemStack(Items.COAL);
            case "noise" -> new ItemStack(Items.NOTE_BLOCK);
            case "education" -> new ItemStack(Items.BOOK);
            default -> ServiceType.byKey(view).icon();
        };
    }

    private void drawPlaceInfo(GuiGraphicsExtractor g, @Nullable CitySummary city, int mouseX, int mouseY) {
        ServiceType st = PlannerClient.placing;
        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        lines.add(st.displayName());
        colors.add(0xFFFFFFFF);
        lines.add(switch (st.kind) {
            case POWER -> Component.translatable("panel.townstead.supplies_power", st.capacity);
            case WATER -> Component.translatable("panel.townstead.supplies_water", st.capacity);
            case SERVICE -> Component.translatable("panel.townstead.covers", st.radius);
        });
        colors.add(0xFFDDDDDD);
        BlockPos cell = PlannerClient.hoverCell;
        if (cell != null && city != null) {
            int cx = Math.floorDiv(cell.getX(), TilePos.CELL), cz = Math.floorDiv(cell.getZ(), TilePos.CELL);
            Component problem = placeProblem(cx, cz);
            if (problem != null) {
                lines.add(problem);
                colors.add(0xFFFF7A6A);
            } else {
                Building preview = PlannerClient.previewCivic(city, st, cx, cz);
                boolean free = minecraft.player != null && minecraft.player.getAbilities().instabuild;
                if (preview != null && !free) {
                    for (var e : TownGrowth.bill(BuildingGenerator.plan(preview)).entrySet()) {
                        int have = Stock.count(city.stock(), e.getKey());
                        lines.add(Component.translatable("road.townstead.cost_line", e.getKey().displayName(), e.getValue(), have));
                        colors.add(have >= e.getValue() ? 0xFFDDDDDD : 0xFFFFB84A);
                    }
                }
                lines.add(Component.translatable("service.townstead.click_to_place"));
                colors.add(0xFFB8B8B8);
            }
        }
        cursorPanel(g, lines, colors, mouseX, mouseY);
    }

    /** The info view's legend, plus what's under the cursor. */
    private void drawInfoLegend(GuiGraphicsExtractor g, @Nullable CitySummary city, @Nullable Building hovered, int mouseX, int mouseY) {
        if (city == null) return;
        String view = PlannerClient.INFO_VIEWS.get(Math.max(0, Math.min(PlannerClient.INFO_VIEWS.size() - 1, PlannerClient.infoView)));
        List<Building> town = ClientLand.get().buildingsOf(city.id());
        var util = PlannerClient.utilities();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("info.townstead." + view));
        switch (view) {
            case "power", "water" -> {
                boolean power = view.equals("power");
                int cap = 0, used = 0, without = 0;
                for (var grid : util.grids().values()) {
                    cap += power ? grid.powerCapacity() : grid.waterCapacity();
                    used += power ? grid.powerUsed() : grid.waterUsed();
                }
                for (Building b : town) {
                    if (b.civic() || b.state == Building.State.ABANDONED) continue;
                    if (!(power ? util.power(b) : util.water(b))) without++;
                }
                lines.add(Component.translatable("info.townstead.supply", used, cap));
                lines.add(Component.translatable("info.townstead.without", without));
            }
            case "level" -> {
                int[] n = new int[4];
                for (Building b : town) if (!b.civic()) n[Math.min(3, b.level)]++;
                lines.add(Component.translatable("info.townstead.levels", n[1], n[2], n[3]));
            }
            case "happiness", "land_value", "pollution", "noise", "education" -> {
                var m = PlannerClient.model();
                if (m == null) break;
                switch (view) {
                    case "happiness" -> {
                        int unhappy = 0;
                        for (Building b : m.working()) if (!b.civic() && m.happiness(b).total() < com.cloudkingtv.townstead.sim.Happiness.UNHAPPY) unhappy++;
                        lines.add(Component.translatable("info.townstead.town_happiness", m.townHappiness(), unhappy));
                    }
                    case "education" -> lines.add(Component.translatable("info.townstead.educated", m.educated, m.adults, m.schooled.size()));
                    default -> {
                        int sum = 0, n = 0;
                        for (Building b : m.working()) {
                            if (!b.zone.residential()) continue;
                            n++;
                            sum += view.equals("land_value") ? m.fields.landValue(b) : view.equals("pollution") ? m.fields.pollution(b) : m.fields.noise(b);
                        }
                        lines.add(Component.translatable("info.townstead.home_average", n == 0 ? 0 : sum / n));
                    }
                }
                BlockPos cell = PlannerClient.hoverCell;
                if (cell != null && !view.equals("happiness") && !view.equals("education")) {
                    int cx = Math.floorDiv(cell.getX(), TilePos.CELL), cz = Math.floorDiv(cell.getZ(), TilePos.CELL);
                    int v = view.equals("land_value") ? m.fields.landValue(cx, cz) : view.equals("pollution") ? m.fields.pollution(cx, cz) : m.fields.noise(cx, cz);
                    lines.add(Component.translatable("info.townstead.here", v));
                }
                lines.add(Component.translatable("info.townstead.legend." + view));
            }
            default -> {
                ServiceType st = ServiceType.byKey(view);
                var m = PlannerClient.model();
                int covered = 0, total = 0;
                for (Building b : town) {
                    if (b.civic()) continue;
                    total++;
                    if (m != null && m.covered(b, st)) covered++;
                }
                lines.add(Component.translatable("info.townstead.coverage", covered, total));
            }
        }
        int w = lines.stream().mapToInt(font::width).max().orElse(60) + 12, h = lines.size() * (font.lineHeight + 2) + 8;
        int x = 6, y = TOP_BAR_H + 6;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, w, h);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x + 6, y + 5 + i * (font.lineHeight + 2), i == 0 ? 0xFFFFE9B0 : 0xFFDDDDDD, true);
        if (hovered != null) drawBuildingInfo(g, hovered, mouseX, mouseY);
    }

    /**
     * Cities: Skylines status icons over buildings that have a problem: no power, no water, waiting for a
     * material, abandoned. Projected from the world through the camera's near plane.
     */
    private void drawStatusIcons(GuiGraphicsExtractor g, @Nullable CitySummary city, int mouseX, int mouseY) {
        if (city == null || minecraft.level == null || !showIcons) return;
        Camera camera = minecraft.gameRenderer.mainCamera();
        Camera.NearPlane plane = camera.getNearPlane(camera.getFov());
        Vec3 tl = plane.getTopLeft(), tr = plane.getTopRight(), bl = plane.getBottomLeft(), br = plane.getBottomRight();
        Vec3 fwd = tl.add(tr).add(bl).add(br).scale(0.25), up = tl.subtract(bl).scale(0.5), left = tl.subtract(tr).scale(0.5);
        Vec3 cam = camera.position();
        var util = PlannerClient.utilities();
        float bob = (float) Math.sin(Util.getMillis() / 300.0) * 1.5f;
        Component tip = null;
        for (Building b : ClientLand.get().buildingsOf(city.id())) {
            List<ItemStack> icons = new ArrayList<>();
            List<Component> why = new ArrayList<>();
            if (b.state == Building.State.ABANDONED) { icons.add(new ItemStack(Items.COBWEB)); why.add(Component.translatable("status.townstead.abandoned")); }
            else if (b.state == Building.State.PAUSED && b.needs != null) { icons.add(b.needs.icon()); why.add(Component.translatable("building.townstead.waiting", b.needs.displayName())); }
            else if (!b.civic() && b.working()) {
                if (!util.power(b)) { icons.add(new ItemStack(Items.REDSTONE)); why.add(Component.translatable("status.townstead.no_power")); }
                if (!util.water(b)) { icons.add(new ItemStack(Items.WATER_BUCKET)); why.add(Component.translatable("status.townstead.no_water")); }
                var model = PlannerClient.model();
                if (model != null && model.happiness(b).total() < com.cloudkingtv.townstead.sim.Happiness.UNHAPPY) {
                    icons.add(new ItemStack(Items.WITHER_ROSE));
                    why.add(Component.translatable("status.townstead.unhappy"));
                }
            }
            if (icons.isEmpty()) continue;
            BuildingGenerator.Plan plan = BuildingGenerator.plan(b);
            Vec3 at = new Vec3((plan.min().getX() + plan.max().getX() + 1) / 2.0, plan.max().getY() + 2, (plan.min().getZ() + plan.max().getZ() + 1) / 2.0);
            Vec3 d = at.subtract(cam);
            double f = d.dot(fwd);
            if (f <= 0.1) continue;
            double t = fwd.lengthSqr() / f;
            double ny = d.dot(up) * t / up.lengthSqr(), nx = -d.dot(left) * t / left.lengthSqr();
            if (Math.abs(nx) > 1.1 || Math.abs(ny) > 1.1) continue;
            int sx = (int) Math.round((nx + 1) / 2 * width), sy = (int) Math.round((1 - ny) / 2 * height + bob);
            if (sy - 10 < TOP_BAR_H || sy + 8 > height) continue; // never over the top bar
            int x0 = sx - icons.size() * 9;
            g.fill(x0 - 2, sy - 10, x0 + icons.size() * 18, sy + 8, 0x90401010);
            for (int i = 0; i < icons.size(); i++) g.item(icons.get(i), x0 + i * 18, sy - 9);
            if (mouseX >= x0 - 2 && mouseX < x0 + icons.size() * 18 && mouseY >= sy - 10 && mouseY < sy + 8) {
                var line = Component.empty();
                for (int i = 0; i < why.size(); i++) { if (i > 0) line.append(" · "); line.append(why.get(i)); }
                tip = line;
            }
        }
        if (tip != null) g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
    }

    private void drawZoningInfo(GuiGraphicsExtractor g, @Nullable CitySummary city, int mouseX, int mouseY) {
        int brush = PlannerClient.zoneBrush;
        Component tool = brush < ZoneType.PAINTABLE.size() ? ZoneType.PAINTABLE.get(brush).displayName() : Component.translatable("zone.townstead.clear");
        List<Component> lines = new ArrayList<>(List.of(Component.translatable("zone.townstead.brush", tool)));
        List<Integer> colors = new ArrayList<>(List.of(brush < ZoneType.PAINTABLE.size() ? 0xFF000000 | ZoneType.PAINTABLE.get(brush).rgb : 0xFFFFFFFF));
        BlockPos cell = PlannerClient.hoverCell;
        if (cell != null) {
            int cx = Math.floorDiv(cell.getX(), TilePos.CELL), cz = Math.floorDiv(cell.getZ(), TilePos.CELL);
            var zoned = ClientLand.get().zone(ZoneRules.cellKey(cx, cz));
            if (zoned != null) { lines.add(Component.translatable("zone.townstead.cell_zoned", zoned.type().displayName())); colors.add(0xFFDDDDDD); }
            if (PlannerClient.zonable(cx, cz) == null) { lines.add(Component.translatable("zone.townstead.no_frontage")); colors.add(0xFFFF9A7A); }
        }
        lines.add(Component.translatable("zone.townstead.drag_hint"));
        colors.add(0xFFB8B8B8);
        cursorPanel(g, lines, colors, mouseX, mouseY);
    }

    private void drawBuildingInfo(GuiGraphicsExtractor g, Building b, int mouseX, int mouseY) {
        List<BuildingPanel.Line> all = BuildingPanel.lines(b);
        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (int i = 0; i < Math.min(3, all.size()); i++) { lines.add(all.get(i).text()); colors.add(all.get(i).color()); }
        lines.add(Component.translatable("panel.townstead.click_for_more"));
        colors.add(0xFFB8B8B8);
        cursorPanel(g, lines, colors, mouseX, mouseY);
    }

    /** Cities: Skylines-style readout by the cursor: road, length, and the bill against what's in the storehouse. */
    private void drawRoadInfo(GuiGraphicsExtractor g, @Nullable CitySummary city, int mouseX, int mouseY) {
        RoadTool roads = PlannerClient.ROADS;
        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        RoadTool.Preview preview = roads.preview(city);
        Component head = Component.translatable("road.townstead.type_mode", roads.type.displayName(),
                Component.translatable("road.townstead.mode." + roads.mode.name().toLowerCase(java.util.Locale.ROOT)));
        lines.add(head);
        colors.add(0xFFFFFFFF);
        if (preview == null) {
            lines.add(Component.translatable(roads.start == null ? "road.townstead.hint_start"
                    : roads.mode == RoadTool.Mode.CURVE && roads.control == null ? "road.townstead.hint_bend" : "road.townstead.hint_end"));
            colors.add(0xFFB8B8B8);
        } else {
            lines.add(Component.translatable("road.townstead.length", Math.round(preview.curve().length())));
            colors.add(0xFFDDDDDD);
            if (roads.mode == RoadTool.Mode.CURVE && roads.control == null) {
                lines.add(Component.translatable("road.townstead.hint_bend"));
                colors.add(0xFFB8B8B8);
            }
            boolean free = minecraft.player != null && minecraft.player.getAbilities().instabuild;
            if (free) {
                lines.add(Component.translatable("road.townstead.free"));
                colors.add(0xFF9EE37D);
            } else if (city != null) {
                for (var e : preview.plan().cost.entrySet()) {
                    int have = Stock.count(city.stock(), e.getKey());
                    lines.add(Component.translatable("road.townstead.cost_line", e.getKey().displayName(), e.getValue(), have));
                    colors.add(have >= e.getValue() ? 0xFFDDDDDD : 0xFFFF7A6A);
                }
            }
            for (RoadPlan.Issue issue : preview.plan().issues) {
                lines.add(Component.translatable(issue.key()));
                colors.add(0xFFFF7A6A);
            }
        }
        cursorPanel(g, lines, colors, mouseX, mouseY);
    }

    private void drawBulldozeInfo(GuiGraphicsExtractor g, @Nullable CitySummary city, int mouseX, int mouseY) {
        Building building = PlannerClient.hoveredBuilding();
        if (building != null) {
            boolean unfinished = building.state != Building.State.ACTIVE;
            cursorPanel(g, List.of(Component.translatable("road.townstead.bulldoze_target",
                            Component.translatable("building.townstead.kind." + BuildingGenerator.plan(building).kind())),
                            Component.translatable(unfinished ? "road.townstead.refund_full" : "road.townstead.refund_half")),
                    List.of(0xFFFFFFFF, unfinished ? 0xFF9EE37D : 0xFFDDDDDD), mouseX, mouseY);
            return;
        }
        RoadSegment seg = PlannerClient.hoveredSegment();
        if (seg == null) {
            cursorPanel(g, List.of(Component.translatable("road.townstead.bulldoze_hint")), List.of(0xFFB8B8B8), mouseX, mouseY);
            return;
        }
        boolean recent = minecraft.level != null && minecraft.level.getGameTime() - seg.placedAt() < RoadEditor.FULL_REFUND_TICKS;
        cursorPanel(g, List.of(Component.translatable("road.townstead.bulldoze_target", seg.type().displayName()),
                        Component.translatable(recent ? "road.townstead.refund_full" : "road.townstead.refund_half")),
                List.of(0xFFFFFFFF, recent ? 0xFF9EE37D : 0xFFDDDDDD), mouseX, mouseY);
    }

    private void cursorPanel(GuiGraphicsExtractor g, List<Component> lines, List<Integer> colors, int mouseX, int mouseY) {
        int w = lines.stream().mapToInt(font::width).max().orElse(40) + 12;
        int h = lines.size() * (font.lineHeight + 2) + 8;
        int x = Math.min(mouseX + 14, width - w - 4), y = Math.min(mouseY + 14, subY() - h - 4);
        if (y < TOP_BAR_H + 2) y = TOP_BAR_H + 2;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, w, h);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x + 6, y + 5 + i * (font.lineHeight + 2), colors.get(i), true);
    }

    private int toolAt(double mouseX, double mouseY) {
        int barW = TOOLS.size() * SLOT + TOOLBAR_PAD * 2;
        int barX = (width - barW) / 2, barY = height - SLOT - TOOLBAR_PAD * 2 - 4;
        if (mouseY < barY + TOOLBAR_PAD || mouseY >= barY + TOOLBAR_PAD + SLOT) return -1;
        int i = (int) Math.floor((mouseX - barX - TOOLBAR_PAD) / SLOT);
        return i >= 0 && i < TOOLS.size() ? i : -1;
    }
}

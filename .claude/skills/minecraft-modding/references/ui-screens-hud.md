# UI: Screens, Widgets, Menus, HUD

All GUI code is **client-only**. Since 26.1 GUI code *extracts* elements into a render state
tree (`GuiRenderState`) that is rendered later. Methods are `extract*`, and the graphics object
is `GuiGraphicsExtractor`.

## GuiGraphicsExtractor essentials (26.x names)

| Want | Call |
|---|---|
| Solid rect / gradient / 1-px lines / outline | `fill(x0,y0,x1,y1,argb)`, `fillGradient(x0,y0,x1,y1,top,bottom)`, `horizontalLine`, `verticalLine`, `outline(x,y,w,h,argb)` |
| Text | `text(font, component, x, y, argb, shadow)`, `centeredText(font, c, cx, y, argb)`, `textWithWordWrap(...)`, `textWithBackdrop(...)` |
| Texture region | `blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, texW, texH[, argbTint])` |
| Atlas sprite (nine-slice aware) | `blitSprite(RenderPipelines.GUI_TEXTURED, spriteId, x, y, w, h[, argbTint])` (sprite id is relative to `textures/gui/sprites/`, no `.png`) |
| Items | `item(stack, x, y)`, `fakeItem(stack, x, y)`, `itemDecorations(font, stack, x, y)` |
| Tooltips | `setTooltipForNextFrame(font, components/tooltip, x, y)` (renders above everything) |
| 3D in GUI | picture-in-picture: `entity(...)`, `skin(...)`, `book(...)`, or a custom PiP renderer |
| Transform | `pose()` is a `Matrix3x2fStack`: `pushMatrix()`, `translate(x,y)`, `scale(sx,sy)`, `rotate(rad)`, `popMatrix()` |
| Clip | `enableScissor(x0,y0,x1,y1)` … `disableScissor()` |
| Layering | `nextStratum()` puts everything after it above everything before. Blur: `blurBeforeThisStratum()` (once per frame) |

Colors are always **ARGB** (`0xFFRRGGBB`). Alpha 0 is invisible, a common bug. `ARGB.color(a, r, g, b)`,
`ARGB.linearLerp(t, c1, c2)` and `ARGB.multiplyAlpha(...)` help with fades.

## A polished screen skeleton

```java
public class JournalScreen extends Screen {
    private static final Identifier PANEL = id("journal/panel");      // textures/gui/sprites/journal/panel.png + nine_slice mcmeta
    private static final int W = 236, H = 180;
    private final long openedAt = Util.getMillis();
    private int left, top;

    public JournalScreen() { super(Component.translatable("screen.mymod.journal")); }

    @Override protected void init() {
        left = (width - W) / 2; top = (height - H) / 2;          // always relative to scaled size
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + W - 70, top + H - 26, 60, 20).build());
        // Recreated on every resize: never keep layout state you can't rebuild here.
    }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        super.extractBackground(g, mx, my, pt);                  // dim/blur world
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        float t = Mth.clamp((Util.getMillis() - openedAt) / 180f, 0f, 1f);  // 180 ms open tween
        float e = Easing.OUT_BACK.apply(t);
        g.pose().pushMatrix();
        g.pose().translate(width / 2f, height / 2f);
        g.pose().scale(0.92f + 0.08f * e, 0.92f + 0.08f * e);
        g.pose().translate(-width / 2f, -height / 2f + (1 - e) * 8f);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, W, H, ARGB.white(t));
        g.centeredText(font, title, width / 2, top + 8, 0xFF3F2A14);
        g.pose().popMatrix();
        super.extractRenderState(g, mx, my, pt);                  // widgets
    }

    @Override public boolean keyPressed(KeyEvent event) {         // 1.21.9+: KeyEvent, not ints
        if (ModKeys.OPEN_JOURNAL.matches(event)) { onClose(); return true; } // same key toggles closed
        return super.keyPressed(event);                            // Esc -> onClose by default
    }

    @Override public boolean isPauseScreen() { return false; }    // keep the world running behind story UIs

    @Override public void onClose() { minecraft.gui.setScreen(null); } // 26.2+: screens live on Gui
}
// open from client code: Minecraft.getInstance().gui.setScreen(new JournalScreen());
```

`ARGB.white(float alpha)` and `ARGB.white(int alpha)` both exist in 26.3.

Input handlers since 1.21.9 take event records: `keyPressed(KeyEvent)`,
`mouseClicked(MouseButtonEvent, boolean doubleClick)`, `mouseReleased(MouseButtonEvent)`,
`mouseDragged(MouseButtonEvent, double dx, double dy)`, `charTyped(CharacterEvent)`. In 26.3
`KeyEvent#key()` is an **SDL scancode**: compare with `InputConstants.KEY_*` or use
`keyMapping.matches(event)`.

**26.3 text input**: a custom text field must call
`Minecraft.getInstance().onTextInputFocusChange(this, focused)` in `setFocused`, or SDL won't
deliver characters. Vanilla `EditBox` already does this.

## Custom widgets with motion

```java
public class ChoiceButton extends AbstractWidget {
    private final Runnable onPress; private float hover; // 0..1 smoothed
    public ChoiceButton(int x, int y, int w, Component msg, Runnable onPress) { super(x, y, w, 20, msg); this.onPress = onPress; }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        float target = isHoveredOrFocused() ? 1f : 0f;
        hover += (target - hover) * 0.25f;                        // frame-rate dependent; fine for UI. Or use a time-based tween
        int bg = ARGB.linearLerp(hover, 0xAA1E1E28, 0xDD3A3156);
        int slide = Math.round(hover * 4);
        g.fill(getX(), getY(), getX() + width, getY() + height, bg);
        g.fill(getX(), getY(), getX() + 2 + slide, getY() + height, 0xFFE0B04A);      // accent bar grows
        g.text(Minecraft.getInstance().font, getMessage(), getX() + 8 + slide, getY() + 6, 0xFFFFFFFF, true);
    }
    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
        onPress.run();
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
}
```

The screen and widget above compile against 26.3 (`onClick(MouseButtonEvent, boolean)`,
`keyPressed(KeyEvent)`, `blitSprite(..., int color)`, `guiWidth()`/`guiHeight()`).

Layouts: vanilla has `LinearLayout`, `GridLayout`, `FrameLayout` and `HeaderAndFooterLayout`
(`layout.addChild(widget)`, `layout.arrangeElements()`, `layout.visitWidgets(this::addRenderableWidget)`).
Use them for anything with more than a few widgets. Scroll lists: extend
`ObjectSelectionList`/`ContainerObjectSelectionList`, or `AbstractScrollArea` for custom content.

## Easing (put this in a shared client util)

```java
public enum Easing {
    LINEAR(t -> t),
    IN_QUAD(t -> t * t), OUT_QUAD(t -> 1 - (1 - t) * (1 - t)),
    IN_OUT_QUAD(t -> t < .5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2),
    OUT_CUBIC(t -> 1 - (float) Math.pow(1 - t, 3)),
    IN_OUT_CUBIC(t -> t < .5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2),
    IN_OUT_SINE(t -> -(Mth.cos(Mth.PI * t) - 1) / 2),
    OUT_BACK(t -> { float c1 = 1.70158f, c3 = c1 + 1; return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2); }),
    OUT_ELASTIC(t -> t == 0 || t == 1 ? t : (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1)),
    OUT_BOUNCE(Easing::bounce);

    private final Float2FloatFunction f;  // it.unimi.dsi.fastutil.floats (bundled with Minecraft)
    Easing(Float2FloatFunction f) { this.f = f; }
    public float apply(float t) { return f.get(Mth.clamp(t, 0f, 1f)); }

    private static float bounce(float t) {
        float n = 7.5625f, d = 2.75f;
        if (t < 1 / d) return n * t * t;
        if (t < 2 / d) return n * (t -= 1.5f / d) * t + .75f;
        if (t < 2.5 / d) return n * (t -= 2.25f / d) * t + .9375f;
        return n * (t -= 2.625f / d) * t + .984375f;
    }
}
```

Time sources:
- **UI tweens**: real time (`Util.getMillis()`). Smooth at any FPS, and runs while paused.
- **World-synced effects** (a HUD element pulsing with a game event): ticks + `partialTick`
  (`deltaTracker.getGameTimeDeltaPartialTick(false)`). Freezes with `/tick freeze`.

## Typewriter text (dialogue, story cards)

```java
// Reveal ~40 chars/sec, with pauses after punctuation; play a voice blip every 2 visible letters.
int visibleChars(String s, long startMs) {
    float budget = (Util.getMillis() - startMs) / 1000f * 40f;
    int i = 0;
    while (i < s.length() && budget > 0) {
        char c = s.charAt(i++);
        budget -= switch (c) { case '.', '!', '?' -> 8f; case ',' -> 3f; default -> 1f; };
    }
    return i;
}
// Render with font.split(FormattedText.of(s.substring(0, n)), maxWidth) and draw each line.
// Clicking or pressing Enter while typing should finish the line instantly, and only advance if it's already complete.
```

Split *styled* components with `font.split(component, width)` and reveal by character count
with a `FormattedCharSequence`-aware helper. Never cut through `§` codes or styles.

## Menus + container screens (inventories, shops, crafting stations)

- Server: a `MenuType` registered with extra data. NeoForge:
  `IMenuTypeExtension.create((id, inv, buf) -> new ShopMenu(id, inv, buf))`, opened with
  `serverPlayer.openMenu(provider, buf -> buf.writeVarInt(npcId))`. Fabric: an
  `ExtendedScreenHandlerType` / `ExtendedMenuProvider` with a `StreamCodec` for the opening data.
- The `AbstractContainerMenu` defines `Slot`s (container slots first, then the player inventory
  at the standard offsets), `quickMoveStack` (shift-click; **always** implement it or the game
  may crash or loop), `stillValid` (distance check), and `DataSlot`s for synced ints
  (progress bars).
- Client: `AbstractContainerScreen<ShopMenu>`. Override `extractBackground` (blit the panel at
  `leftPos/topPos`) and `extractLabels` (coordinates relative to the panel). Register it with
  NeoForge `RegisterMenuScreensEvent` (`event.register(MENU.get(), ShopScreen::new)`) or Fabric
  `MenuScreens.register(...)`.
- Buttons in a container screen that change server state send a payload, or use
  `menu.clickMenuButton(player, id)` via `minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id)`.

## HUD overlays

NeoForge:
```java
@SubscribeEvent // mod bus, client
static void layers(RegisterGuiLayersEvent e) {
    e.registerAbove(VanillaGuiLayers.HOTBAR, id("quest_tracker"), QuestTrackerHud::extract);
    e.registerAboveAll(id("cinematic_bars"), CinematicBars::extract);   // letterbox above everything
}
// GuiLayer: void render(GuiGraphicsExtractor g, DeltaTracker delta)
// Hide/replace vanilla layers: RenderGuiLayerEvent.Pre (game bus, cancel by name), or e.replaceLayer / e.wrapLayer
```
Vanilla layer ids live in `VanillaGuiLayers` (`HOTBAR`, `CROSSHAIR`, `PLAYER_HEALTH`,
`FOOD_LEVEL`, `EXPERIENCE_LEVEL`, `EFFECTS`, `BOSS_OVERLAY`, `SCOREBOARD_SIDEBAR`,
`OVERLAY_MESSAGE`, `TITLE`, `CHAT`, `TAB_LIST`, `SUBTITLE_OVERLAY`, …).

Fabric:
```java
HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("quest_tracker"), QuestTrackerHud::extract);
// also: attachElementAfter, addFirst, addLast, replaceElement, removeElement
```

HUD rules: respect `minecraft.options.hideGui` (F1), stay out of vanilla's regions (hotbar,
chat, boss bars, sidebar), anchor to screen edges using `g.guiWidth()`/`g.guiHeight()`, fade in
and out instead of popping, keep text at least 1 px from edges and shadowed. Test at GUI scales
1–4 and in a narrow window.

## Toasts and titles

- Toast: implement `Toast` (render via the extractor, return `Toast.Visibility`) and add it via
  `minecraft.gui.toastManager().addToast(...)`. Good for "Quest updated" or "Skill unlocked".
- Server-driven big text: `ClientboundSetTitleTextPacket`/`SetSubtitleText`/`SetTitlesAnimation`
  (what `/title` uses). Free and multiplayer-safe for chapter cards.
- Action bar: `serverPlayer.sendSystemMessage(component, true)` (overlay message).

## UI design checklist

- One accent color, one panel style, consistent 4/8 px spacing grid, aligned to whole GUI pixels.
- Every interactive element has hover, focus (keyboard Tab), pressed and disabled states,
  plus a click sound.
- Esc always closes. Enter confirms the primary action. Number keys 1–9 pick dialogue choices.
- Long text wraps and scrolls. Nothing overflows at GUI scale 4 with a 854×480 window.
- Every string is translatable and leaves room for 30% longer translations.
- Narration: give widgets meaningful messages so the narrator can read them.

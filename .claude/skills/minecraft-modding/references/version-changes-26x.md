# What Changed: 1.21.11 → 26.1 → 26.2 → 26.3

These are the breaking vanilla changes most likely to trip up code written from memory. The
full, authoritative lists are ChampionAsh5357's primers (CC-BY 4.0):
`https://github.com/neoforged/.github/blob/main/primers/<26.1|26.2|26.3>/index.md`
(rendered at `docs.neoforged.net/primer/docs/<version>/`). When porting, read the primer for
**every** version you cross, and search it for each class you touch.

## 26.1 (March 2026): the big reset

- **Unobfuscated + Java 25.** Mojang's names are in the jar. Yarn is gone. Every mod from
  1.21.11 or earlier must at least be recompiled. Fabric uses the non-remapping Loom
  (`net.fabricmc.fabric-loom`) and drops `modImplementation`/remap tasks.
- **`ResourceLocation` → `Identifier`** (`Identifier.fromNamespaceAndPath(ns, path)`,
  `Identifier.withDefaultNamespace(path)`). This happened in 1.21.11, so it's no longer optional
  when coming from older code.
- **GUI "extract" rename**: `GuiGraphics` → `GuiGraphicsExtractor`.
  `Renderable#render` → `extractRenderState`. `AbstractWidget#renderWidget` → `extractWidgetRenderState`.
  `AbstractContainerScreen#renderBg` → `Screen#extractBackground`, `renderLabels` → `extractLabels`.
  Graphics methods drop `draw/render/submit` prefixes and `*String*` becomes `*Text*`:
  `drawString` → `text`, `drawCenteredString` → `centeredText`, `hLine` → `horizontalLine`,
  `renderOutline` → `outline`, `renderItem` → `item`, `submitEntityRenderState` → `entity`.
- **Model render layers are automatic.** `ItemBlockRenderTypes` is gone, and models no longer
  declare `render_type`. The layer comes from texture alpha: any translucent pixel →
  TRANSLUCENT, any alpha-0 pixel → CUTOUT, else SOLID. Force it with a texture *material object*:
  `"pane": { "sprite": "minecraft:block/glass", "force_translucent": true }`.
  `Material` now means a model texture reference. `SpriteGetter` → `MaterialBaker`.
- `BlockRenderDispatcher` and `ItemRenderer` are removed; block and item models go through the
  feature submission pipeline. `VertexConsumer#putBulkData` → `putBlockBakedQuad`/`putBakedQuad`
  with a `QuadInstance` (color + light + overlay).
- **Camera is a render state**: `Camera#setup` → `update`, plus `extractRenderState` →
  `CameraRenderState` (holds `fogData`). Entity/BE `submit(...)` methods receive `CameraRenderState`.
- Feature rendering split into solid and translucent passes (`renderSolid`/`renderTranslucent`).
- `RenderPipeline` takes `ColorTargetState` and `DepthStencilState`. `DepthTestFunction` → `CompareOp`.
- `ChunkPos` is now a record. `Level#random` is protected. `interactAt` was removed.
- Data: villager trades are data-pack driven. Loot types were unrolled. A validation overhaul.
  Item "stack templates" (`ItemStackTemplate`) appear in recipes/dialog bodies. Saved data was
  split out of the primary level data. World clocks and time markers were added.

## 26.2 (June 2026): Vulkan and the feature takeover

- **Vulkan backend** (selectable in options). `com.mojang.blaze3d.vulkan` parallels `opengl`.
  **Never call raw GL**; go through the Blaze3D/RenderPipeline API.
- `MultiBufferSource` is **removed**. All dynamic rendering goes through
  `SubmitNodeCollector`/`SubmitNodeStorage` → `FeatureRenderDispatcher`. Custom features need a
  `SubmitNode` record, a `FeatureRenderPhase`, and a `FeatureRenderer` (or
  `RenderTypeFeatureRenderer`, which gives you `getVertexBuilder(renderType)`).
- `PictureInPictureRenderer` lost its buffer-source constructor. `renderToTexture(state, poseStack, SubmitNodeCollector)`.
- `GpuFormat` replaces `TextureFormat` and vertex element formats. `VertexFormat` is built with
  `addAttribute`. `PrimitiveTopology`/`IndexType` are separate enums. `BindGroupLayout` holds
  samplers and uniforms (`RenderPipeline.Builder#withBindGroupLayout`). `BlendFactor`/`BlendOp`.
- **`Font` draw methods removed.** Use `GuiGraphicsExtractor#text` in GUIs and
  `OrderedSubmitNodeCollector#submitText` in the world, or `font.prepareText(...)` + a `GlyphVisitor`.
- **Gui reorganized**: the current screen lives on `Gui`, and the HUD is a separate `Hud` class.
  `Minecraft.getInstance().screen` → `Minecraft.getInstance().gui.screen()`.
  `minecraft.setScreen(s)` → `minecraft.gui.setScreen(s)`. Toasts: `minecraft.gui.toastManager()`.
  Overlay message: `gui.hud.setOverlayMessage(...)`.
- **`ChatFormatting` mostly gutted.** Use `Style#withColor`, `withBold`, `withItalic`, … on components.
- `ShapeRenderer` → `submitShapeOutline`. Gizmos render through `GizmoFeatureRenderer`.
- Advancements and entity sub-predicates reworked. More `ResourceKey`s. Tag providers changed.

## 26.3 (September 2026): SDL, Renderpearl, reloadable registries

- **LWJGL now uses SDL instead of GLFW.** Key codes are SDL scancodes: use `InputConstants.KEY_*`
  or `SDLScancode.SDL_SCANCODE_*`, never raw GLFW ints. `KeyEvent#key` = scancode,
  `KeyEvent#keycode` (renamed from `scancode`) = SDL keycode. Mouse buttons are
  `SDLMouse.SDL_BUTTON_*`. Modifiers are an `SDL_KMOD_*` mask.
- **Text input must be started and stopped explicitly.** Any custom text-entry widget must call
  `Minecraft.getInstance().onTextInputFocusChange(this, focused)` from `setFocused`, or typing
  won't arrive.
- **Blaze3D render API → `com.mojang.renderpearl`** (`api.*`, `backend.*`, `frontend.*`).
  `RenderSystem#outputColorTextureOverride`/`outputDepthTextureOverride` are removed. Render to
  textures by creating a `RenderPass` with explicit targets around a `PreparedFrame`.
- **Shaders**: compiled with shaderc for both GL and Vulkan. `#moj_import` → `#include <ns:file.glsl>`.
  Every `in`/`out` needs `layout(location = N)`. Add
  `#extension GL_ARB_separate_shader_objects : require`. Macros:
  `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`, `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`,
  `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE`.
- **Order-independent transparency** (Wavelet OIT) under "improved transparency". Several
  translucent phases collapse into `oitTranslucent` when enabled. Phase renames:
  `seeThroughNameTags` → `seeThrough`, `gizmos` → `translucentGizmos`, `alwaysOnTop` → `alwaysOnTopGizmos`.
- **Palette metadata** (`"palette": {"base_palette": "minecraft:trim_base"}` in `.png.mcmeta`)
  for entity textures, via `PalettedTextureManager`. Trims use `palette_id` and `trim_overrides`.
- `ItemQuads` (solid/translucent split) for custom `ItemModel`s: `ItemQuads.split(quads)`, cached.
  `submitCrumblingOverlay` is separate from `submitModel`.
- **Reloadable datapack registries**: loot tables, loot conditions/functions, advancements,
  recipes, number providers and slot sources are now datapack registries reloadable by `/reload`.
  References become `Holder`/`HolderSet`. Datagen providers implement
  `SingleRegistryBootstrap`/`MultiRegistryBootstrap`. `AdvancementSubProvider` is an abstract
  class taking a `BootstrapContext`; reference parents by `Identifier`.
- **`ContextAwarePredicate` removed** → `Optional<Holder<LootItemCondition>>` in criterion
  trigger instances.
- Numbers split into float and int providers. Block transformers. Datapack brewing recipes.
  Data components for cooking fuel, compostable, villager food and "hiding among mobs".
  Worldgen: features without `ConfiguredFeature`, density function rewrite, surface rules →
  materials.
- Mojang ships Java 25 to players.

## Found while building Townstead on 26.3 (verified by compiling)

- **Blocks no longer declare a codec.** Drop `MapCodec CODEC = simpleCodec(...)` and the `codec()`
  override (e.g. `HorizontalDirectionalBlock` has no abstract `codec()` any more).
- **`BlockEvent.BreakEvent` → `net.neoforged.neoforge.event.level.block.BreakBlockEvent`**
  (cancellable, `getPlayer()`).
- **Loot table JSON:** a pool takes a single `"condition": {"type": "minecraft:survives_explosion"}`
  object (not the old `"conditions": [{"condition": ...}]` list) and tables carry `"random_sequence"`.
  Copy the shape from a vanilla file in the sources jar's `data/` folder.
- **Key categories:** create `new KeyMapping.Category(id)` and register it with
  `RegisterKeyMappingsEvent#registerCategory`. `Category.register` is deprecated. The label key is
  `key.category.<namespace>.<path>`.
- Some `Items` fields are now **collections**, e.g. `Items.LIGHTNING_ROD` is a
  `WeatheringCopperCollection<Item>`. `Items.WHITE_BANNER` isn't a plain field either. Check before
  using an item as an icon.
- `GameProfile` is a record: `profile.name()`, `profile.id()`.
- `ModConfig.Type` (FML 12) is `LOCAL`, `CLIENT`, `SYNCED`, `STARTUP`: no `COMMON`, and the old
  `SERVER` (per-world, synced to clients) is now `SYNCED`.
- **`EditBox` reads `KeyEvent#keycode()` (via `shortcutKey()`), screens usually read `key()`.**
  Compare your own bindings with `event.key()` against `InputConstants.KEY_*` (scancodes, layout
  independent, right for WASD). Text editing uses keycodes, so synthetic events need both.
- `EditBox#setHighlightPos(0)` after `moveCursorToEnd(false)` selects all text: open a rename field
  with the old name selected so typing replaces it.
- **Don't build `ItemStack`s in static initializers of classes that payloads or registration touch**
  (enums used in a `StreamCodec`, for example). `RegisterPayloadHandlersEvent` runs before item
  components are bound, so `new ItemStack(...)` there throws `NullPointerException: Components not
  bound yet` and the whole mod fails to load. Keep the `Item` and make the stack on demand.
- `GuiGraphicsExtractor#setTooltipForNextFrame(font, component, x, y)`: the tooltip is placed relative
  to the point you pass, not the real mouse, so anchor it below a top bar to keep it off the label.

## Porting procedure

1. Bump `gradle.properties` to the target and update the Gradle wrapper
   (`./gradlew wrapper --gradle-version <x>`). Refresh.
2. Compile, collect the errors, and look each missing symbol up in the primer's "List of
   Changes/Removals" (search for the class name).
3. Fix the mechanical renames first, then the structural ones (rendering, registries).
4. Run client, server and gametests. Check textures (auto render layers can change how
   cutout/translucent textures look) and keybinds (SDL codes).

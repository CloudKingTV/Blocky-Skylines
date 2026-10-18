# Textures, Models and Assets

## Resource layout cheat sheet

```
assets/<modid>/
  lang/en_us.json                           # translations
  textures/block/*.png, textures/item/*.png # atlas textures (16x16 baseline)
  textures/entity/<mob>.png                 # entity skins (sized to the model's texture size)
  textures/gui/sprites/<path>.png           # GUI atlas sprites (blitSprite, nine-slice via .mcmeta)
  textures/gui/<screen>.png                 # full GUI backgrounds (blit)
  textures/particle/*.png
  models/block/*.json, models/item/*.json   # geometry
  blockstates/<block>.json                  # state -> model mapping
  items/<item>.json                         # CLIENT ITEM definition (required for every item)
  sounds.json, sounds/**/*.ogg              # mono .ogg for positional sounds
  particles/<particle>.json                 # which textures a particle type uses
  font/*.json                               # custom fonts/glyphs (icons in text!)
  shaders/core|post|include/*               # see rendering-and-vfx.md
  geckolib/models|animations/...            # GeckoLib only
  neoforge/animations/entity/*.json         # NeoForge JSON entity animations
data/<modid>/...                             # recipes, loot, advancements, tags, your JSON content
```

`Identifier.fromNamespaceAndPath(MODID, "block/rune_stone")` in a model → `assets/<modid>/textures/block/rune_stone.png`.

## Making textures that look like they belong

Vanilla-plus rules that separate pro textures from amateur ones:

1. **Texel density**: blocks and items are 16×16. Entities use 1 texel per model pixel (a 64×64
   skin for a humanoid). Don't mix resolutions in one object. Higher-res packs are opt-in.
2. **Limited palettes with hue-shifted ramps**: 4–6 shades per material. Shift hue toward warm
   (yellow/orange) in highlights and cool (blue/purple) in shadows instead of only changing
   brightness. Desaturate the extremes slightly. `scripts/texture_tools.py ramp` generates ramps.
3. **Light from the top-left**: highlights on top and left edges, shadows bottom-right. Items
   get a 1-px dark outline on the bottom/right silhouette, as in vanilla.
4. **No pillow shading** (dark rim around every shape) and no noise filters. Place clusters of
   2–4 texels deliberately. Avoid lone single-pixel "jaggies" in outlines.
5. **Read at 100%**: check the texture at 1× and 2× in game. Item silhouettes must read in a
   hotbar slot at GUI scale 2.
6. **Tileability**: block textures must tile. Check the seams by previewing 3×3 (`texture_tools.py tile-preview`).
7. **Alpha discipline (26.x)**: render layers are inferred from alpha. Fully opaque → SOLID.
   Any alpha-0 pixel → CUTOUT. Any partial alpha → TRANSLUCENT (sorted, more expensive). Stray
   semi-transparent pixels from a soft brush will silently make a block translucent:
   `texture_tools.py check` reports the layer a PNG will get.
8. **PNG only**, sRGB, no color profile weirdness. Use power-of-two sizes for atlas textures.

### Generating textures as an agent

You can author textures programmatically with Python and Pillow. Draw on a small canvas at
1:1 using explicit palette indices, never by resizing a large painted image. Use nearest
neighbor for any scaling. Workflow:

1. Define the palette ramp(s) (`texture_tools.py ramp --base "#6a8f3c" --steps 5`).
2. Write the pixel grid as a list of strings (one char per palette index) in a small script,
   render it to PNG, and save it into `assets/<modid>/textures/...`.
3. Run `texture_tools.py preview` (8× nearest-neighbor upscale, and a 3×3 tile for blocks), then
   **look at the preview image** (the Read tool shows images) before calling it done.
4. Run `texture_tools.py check` for size, alpha class and power-of-two.

For complex art (detailed mobs, GUI panels), generate a solid first pass and tell the user it
is a placeholder that an artist can refine in Aseprite/Blockbench. Don't oversell programmatic art.

## Texture metadata (`<texture>.png.mcmeta`)

```json5
{
  "animation": {            // vertical strip of frames (16x16 with 8 frames -> 16x128)
    "frametime": 2,          // ticks per frame
    "interpolate": true,     // smooth blending between frames (great for glows, liquids)
    "frames": [0, 1, 2, 3, {"index": 2, "time": 6}]
  },
  "texture": { "mipmap_strategy": "cutout", "alpha_cutoff_bias": 0.0 }, // mean|cutout|strict_cutout|dark_cutout
  "gui": { "scaling": { "type": "nine_slice", "width": 32, "height": 32,
           "border": 4, "stretch_inner": false } }               // stretch | tile | nine_slice
}
```

26.3 adds `"palette": {"base_palette": "minecraft:trim_base"}` for paletted **entity**
textures (`PalettedTextureManager`), which gives cheap color variants without duplicate PNGs.

## Block models and blockstates

```json5
// assets/<modid>/blockstates/rune_stone.json
{ "variants": { "lit=false": { "model": "<modid>:block/rune_stone" },
                "lit=true":  { "model": "<modid>:block/rune_stone_lit" } } }

// assets/<modid>/models/block/rune_stone_lit.json
{
  "parent": "minecraft:block/cube_all",
  "textures": { "all": "<modid>:block/rune_stone_lit" }
}
```

Custom geometry: `elements` with `from`/`to` (0–16 units), per-face `uv`, `texture`, `cullface`,
`tintindex`, an element `rotation` (any angle in recent versions), and `"shade": false` for
glowing parts. **Glowing details**: put the glowing bits on their own element with
`"light_emission": 15` (baked into the quads since 1.21.2), so they render full-bright even in
the dark. Combine with an `interpolate`d animated texture for a pulse.

Force translucency per texture (26.1+):
`"textures": { "glass": { "sprite": "<modid>:block/tinted_crystal", "force_translucent": true } }`.

For big or curved shapes, use Blockbench's "Java Block/Item" format (export JSON), or
NeoForge's OBJ loader (`"loader": "neoforge:obj"`). Keep each element on whole or half pixels
so the texels stay aligned.

## Item models (client items)

Since 1.21.4 every item needs `assets/<ns>/items/<item>.json`:

```json5
{ "model": { "type": "minecraft:model", "model": "<modid>:item/quest_scroll" } }
```

Useful model types:
- `minecraft:condition` with `"property": "minecraft:using_item"` (bows, wands while charging)
- `minecraft:select` on `minecraft:component` / `custom_model_data` strings (variants without new items)
- `minecraft:range_dispatch` on `minecraft:use_duration`, `damage`, `count`, `cooldown`
  (charge-up stages, durability looks)
- `minecraft:composite` (layer several models), `minecraft:special` (code-rendered: shields,
  banners, heads; custom ones go through NeoForge `RegisterSpecialModelRendererEvent`)
- Custom properties: NeoForge `RegisterConditionalItemModelPropertyEvent` /
  `RegisterSelectItemModelPropertyEvent` / `RegisterRangeSelectItemModelPropertyEvent`

`models/item/quest_scroll.json`: `{"parent": "minecraft:item/generated", "textures": {"layer0": "<modid>:item/quest_scroll"}}`
(`item/handheld` for tools held like swords).

**Datagen these.** NeoForge: extend `ModelProvider` (`registerModels(BlockModelGenerators, ItemModelGenerators)`).
Fabric: `FabricModelProvider` (`generateBlockStateModels`/`generateItemModels`). Both reuse
vanilla generators, e.g. `blockModels.createTrivialCube(block)` and
`itemModels.generateFlatItem(item, ModelTemplates.FLAT_ITEM)`.

## Entity textures and emissive layers

- The texture size must match `LayerDefinition.create(mesh, texW, texH)`, and UVs come from
  `texOffs(u, v)`. Blockbench's "Modded Entity" format manages the UV layout and can export the
  Java `LayerDefinition`.
- Glowing eyes or runes: add a `RenderLayer` that submits the model again with the `eyes`
  render type, the way vanilla's `EyesLayer` does for spiders and endermen, using a texture that
  contains only the glowing pixels. GeckoLib has `AutoGlowingGeoLayer`, which uses
  `<texture>_glowmask.png`.
- Variants (biome or color skins): store the variant in `SynchedEntityData` (or a registry
  holder), extract it into the render state, and pick the texture in `getTextureLocation(state)`.

## GUI art

- Panels: one nine-slice sprite in `textures/gui/sprites/` plus `.mcmeta` `nine_slice`. It
  scales to any size without blurring.
- Draw at the native pixel grid, typically 1 GUI pixel = 1 texel, and keep borders 1–2 px
  thick to match vanilla.
- Icons in text: a bitmap font provider (`assets/<ns>/font/icons.json`, mapping private-use
  codepoints like `` to a PNG grid). This puts coin and heart glyphs inside dialogue and
  quest text.

## Sounds

```json5
// assets/<modid>/sounds.json
{
  "voice_blip": { "sounds": [ {"name": "<modid>:voice/blip1", "volume": 0.6},
                               {"name": "<modid>:voice/blip2", "volume": 0.6, "pitch": 1.1} ],
                  "subtitle": "subtitles.<modid>.voice_blip" },
  "music.tower_theme": { "sounds": [{"name": "<modid>:music/tower", "stream": true}] }
}
```

- Positional sounds must be **mono** `.ogg`. Stereo only works non-positionally (music, UI).
  `"stream": true` for long tracks.
- Register `SoundEvent`s (`SoundEvent.createVariableRangeEvent(id)`) and play server-side with
  `level.playSound(null, pos, sound, SoundSource.NEUTRAL, vol, pitch)` (the `null` player means
  everyone hears it), or client-side via `Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch))`.
- Variation: 2–4 samples per event with small random pitch (0.9–1.1) keep repetition from
  getting irritating.
- Always add a `subtitle` translation key.

## Language files

`assets/<modid>/lang/en_us.json`: `"item.<modid>.quest_scroll"`, `"block.<modid>.rune_stone"`,
`"entity.<modid>.elder"`, `"key.<modid>.skip_cutscene"`, `"key.category.<modid>"` (or the 26.x
`KeyMapping.Category` id), `"<modid>.dialogue.elder.greeting"`, `"subtitles.<modid>.voice_blip"`.
Generate them with a `LanguageProvider` (NeoForge) / `FabricLanguageProvider`, so a missing
key fails the build instead of showing raw keys in game.

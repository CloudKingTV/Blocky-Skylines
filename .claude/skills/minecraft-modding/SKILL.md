---
name: minecraft-modding
description: Expert Minecraft Java Edition modding for NeoForge and Fabric on the 26.x line (26.1–26.3+, unobfuscated, Java 25). Use for any Minecraft mod work - project setup, registration, items/blocks/entities, custom textures and models, Blockbench/GeckoLib animations, GUI screens and HUD overlays, in-game cutscenes and camera control, NPCs with dialogue, quests/progression/advancements, networking, particles/shaders/VFX, data packs, mixins, porting between versions, and debugging crashes. Trigger even when the user just says "mod", "Minecraft", "NeoForge", "Fabric", "GeckoLib", "Blockbench", a vanilla class name (Screen, EntityRenderer, GuiGraphicsExtractor, PoseStack), or describes a gameplay feature they want in Minecraft.
---

# Minecraft Modding (pro level, 26.x era)

This skill makes you a senior Minecraft modder. It covers the craft (what makes a mod feel
polished) and the current engineering facts (what the 26.x code actually looks like). Read
this file first, then open only the reference files the task needs.

## 0. Ground truth first: the API moves fast

Minecraft's code changes a lot every release, and your training data is mostly 1.20/1.21 era.
Since 26.1 (March 2026) the game ships **unobfuscated with Mojang's names**, needs **Java 25**,
and the GUI/render APIs were renamed (`GuiGraphics` became `GuiGraphicsExtractor`, `render*`
became `extract*`). 26.2 added a Vulkan backend and removed `MultiBufferSource`. 26.3 moved
windowing/input to SDL and Blaze3D to `com.mojang.renderpearl`.

Rules:
1. **Pin the version before writing code.** Check `gradle.properties` (`minecraft_version`,
   `neo_version` / `loader_version`, `fabric_api_version`). If there's no project yet, use the
   versions in `references/setup-and-toolchain.md`.
2. **Never trust memory for a signature in render/GUI/input code.** Confirm it in one of:
   - the decompiled sources in the IDE / Gradle cache (best: they're exact for the pinned version)
   - the porting primers: `https://github.com/neoforged/.github/tree/main/primers/<version>/index.md`
     (one per version, listing every vanilla rename/removal; loader-agnostic)
   - the NeoForge docs (`github.com/neoforged/Documentation`, `docs/`) or the Fabric docs
     (`github.com/FabricMC/fabric-docs`, `develop/`, with *compiled* example code in
     `reference/latest/src`)
3. If a doc and the decompiled source disagree, **the source wins**. The docs trail behind
   (e.g. NeoForge's PiP doc still shows `MultiBufferSource`, which 26.2 removed).
4. Say so when you couldn't verify a signature, and mark it in a code comment
   (`// verify: signature changed in 26.2`).

`references/version-changes-26x.md` summarizes the breaking changes from 1.21.11 to 26.3.

## 1. Pick the loader

| Situation | Choose |
|---|---|
| Content-heavy mod (entities, GUIs, progression, cutscenes) and no constraint | **NeoForge**: richer events (camera, GUI layers, render-state modifiers, JSON entity animations, data attachments with sync) |
| Lightweight/client-side tweak, or the user's pack is Fabric | **Fabric** + Fabric API (mixins are the norm) |
| Must ship on both | Common module + loader modules (ModDevGradle + Fabric Loom in one multi-project build). Keep vanilla-only code in `common` and put loader glue behind small interfaces |

If the user already has a project, use its loader. Don't switch loaders without asking.

## 2. Architecture rules every pro mod follows

- **Two sides, always.** Physical client vs dedicated server; logical client vs logical server.
  Gameplay state and decisions live on the **server**. The client renders, animates and predicts.
  Never reference `net.minecraft.client.*` from common code: put client code in a
  `Dist.CLIENT`-only class (NeoForge `@Mod(value=MODID, dist=Dist.CLIENT)`) or Fabric's `client`
  source set. Check `level.isClientSide()` before mutating anything.
- **Never trust the client.** Every serverbound packet is validated: distance, permissions,
  ownership, cooldowns, item presence.
- **Render state extraction.** Entities, block entities, the camera and the GUI all follow
  extract-then-submit: copy what you need into a render state (`extractRenderState`), then
  `submit` features from that state alone. Don't touch the entity or world during `submit`.
- **Data-driven over hard-coded.** Dialogue trees, quests, cutscenes, trades, loot and
  advancements belong in JSON (data pack `data/` or resource pack `assets/`) loaded through
  codecs, so designers can iterate with `/reload` or F3+T.
- **Codecs everywhere.** `Codec`/`MapCodec` for disk and JSON, `StreamCodec` for network.
  One record plus two codecs is the normal shape of any data type.
- **Interpolate with partial ticks.** The game ticks at 20 TPS but renders at hundreds of FPS.
  Anything that moves on screen lerps between the previous and current tick values with
  `partialTick`, or uses real time (`Util.getMillis()`) for UI tweening.
- **Translations, not literals.** Every player-facing string is a `Component.translatable` key
  in `assets/<modid>/lang/en_us.json`.
- **Datagen for boilerplate.** Generate models, blockstates, loot, recipes, tags, lang and
  advancements with data generators instead of hand-writing hundreds of JSON files.

## 3. Task router: which reference to open

| The user wants... | Open |
|---|---|
| New project, Gradle errors, versions, mixins/access transformers, publishing | `references/setup-and-toolchain.md` |
| Porting, or "this method doesn't exist" | `references/version-changes-26x.md` |
| Registering things, events, data storage, codecs, networking, configs | `references/core-patterns.md` |
| Custom textures, pixel art, item/block models, animated or emissive textures, sounds, lang | `references/textures-models-assets.md` |
| Custom mobs, entity models, keyframe animation, GeckoLib, player animations | `references/entities-and-animation.md` |
| Screens, menus, widgets, HUD overlays, UI animation and juice | `references/ui-screens-hud.md` |
| Cutscenes, camera paths, letterboxing, fades, screen shake, cinematic sequencing | `references/cutscenes-and-camera.md` |
| NPCs, dialogue trees, choices, shops/trades, AI behavior | `references/npcs-and-dialogue.md` |
| Quests, skill trees, unlocks, advancements, XP/levels, progression tracking | `references/progression-systems.md` |
| Particles, custom render pipelines, shaders, world-space VFX, sound design | `references/rendering-and-vfx.md` |
| Testing, crashes, performance, profiling, release checklist | `references/testing-debugging.md` |

Most features touch three or four of these. A "boss intro" is cutscene + entity animation +
particles + HUD (boss bar) + networking.

## 4. Workflow for a feature

1. **Clarify the experience** in one or two sentences ("the player right-clicks the elder, the
   camera pans to the ruined tower, the elder's lines type out with voice blips, the player picks
   a reply and the quest starts"). Polish lives in these details.
2. **Split by side**: what does the server own (state, triggers, validation), what does the
   client own (camera, rendering, UI, sound timing), and which payloads connect them?
3. **Decide what's data**: JSON schema plus codec for designer-tunable content.
4. **Scaffold** registration, then server logic, then networking, then client presentation,
   then assets, then datagen.
5. **Verify** signatures against source for the pinned version. Build with `./gradlew build`,
   then run `runClient` / `runServer` and `runGameTestServer`. A dedicated-server launch catches
   client-class leaks.
6. **Polish pass**: easing on every motion, sound on every interaction, a particle where it reads
   well, readable text at GUI scale 1–4, keyboard and controller-free paths (Esc closes, Enter
   confirms), localization keys, and an option to skip or disable cinematic effects.

## 5. Polish standards ("clear and amazing")

- **Motion**: never move linearly unless it's mechanical. Ease-out for things arriving,
  ease-in for leaving, ease-in-out for camera moves. Overshoot/back easing for UI pop-ins.
  Use anticipation, follow-through and overlapping action for creatures (tails lag the body,
  heads lead turns).
- **Timing**: UI transitions take 120–250 ms; camera cuts in cutscenes take 1–4 s per shot;
  hit-stop takes 2–4 ticks. Respect `/tick freeze` and pause where it makes sense.
- **Feedback stack** for any important action: sound + particle + small camera or UI response
  + state change. Keep screen shake subtle and make it configurable.
- **Readability**: GUI textures on the pixel grid, at integer scale, using nine-slice sprites
  for resizable panels. Text has a shadow or backdrop over the world. Contrast stays at 4.5:1
  or better.
- **Vanilla-plus aesthetic**: match Minecraft's 16×16 texel density, palette discipline and
  lighting direction (top-left light) unless the user wants a distinct style.
- **Accessibility and comfort**: subtitles for voiced/sound cues, a reduce-motion config
  (disables shake, FOV punches and fast camera moves), cutscene skip (hold a key), and
  colorblind-safe indicators.

## 6. Environment notes for this agent

- Building needs network access to `maven.neoforged.net` (NeoForge), `maven.fabricmc.net`
  (Fabric), `libraries.minecraft.net`, `piston-meta.mojang.com`, `piston-data.mojang.com`,
  `resources.download.minecraft.net` (game jars and assets), `services.gradle.org`, and any
  library maven (GeckoLib uses `dl.cloudsmith.io`). If the sandbox blocks them, say which
  host failed and still write the code. Don't fake a successful build.
- Java 25: if it's missing (and foojay is blocked), `apt-get install openjdk-25-jdk-headless` and
  `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` before `./gradlew`. Fabric Loom needs
  Gradle itself on Java 25. See setup-and-toolchain.md.
- **Check APIs against the real code**: after one NeoForge build, unzip
  `build/moddev/artifacts/minecraft-patched-*-sources.jar` and grep it. Faster and more reliable
  than any doc. The skill's main snippets (entity, model, keyframes, renderer, particle,
  screen, widget, HUD layers, payloads, attachment, SavedData, command, camera/input mixins)
  were compiled against NeoForge 26.3.0.90 on 2026-10-18.
- GitHub is usually reachable: clone MDKs (`NeoForgeMDKs/MDK-<mc>-ModDevGradle`), the Fabric
  example mod (`FabricMC/fabric-example-mod`), docs and primers to check APIs.
- `scripts/scaffold.sh <neoforge|fabric> <modid> <package> [mc] [dir] ["Name"]` creates a new
  project from the official templates with the mod id, package and name renamed.
- `scripts/texture_tools.py` handles pixel-art work: `ramp` (hue-shifted palettes), `render`
  (text grid → PNG), `preview` (8× and 3×3 tile previews you can view with Read), `check`
  (size and the render layer implied by alpha), `nine-slice` (GUI panel + mcmeta), and `strip`
  (animated texture + mcmeta). Requires Pillow.

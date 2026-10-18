# Rendering and VFX: particles, world rendering, shaders, juice

Hard rule for 26.2+: **never issue raw OpenGL calls.** The game may run on Vulkan, and
26.3 moved the render API to `com.mojang.renderpearl`. Everything goes through features
(`SubmitNodeCollector`), `RenderPipeline`s and render passes.

## Particles (first choice for VFX)

1. Register a type: `PARTICLES.register("rune_spark", () -> new SimpleParticleType(false))`
   (`true` = always render even at "minimal" particle setting; use it sparingly). For
   parameters (color, size, target) make a `ParticleOptions` record with a `MapCodec` and
   `StreamCodec`, and a `ParticleType` subclass returning them.
2. Describe textures: `assets/<ns>/particles/rune_spark.json` →
   `{"textures": ["<ns>:rune_spark_0", "<ns>:rune_spark_1", "<ns>:rune_spark_2"]}`, with PNGs
   in `textures/particle/`.
3. Client provider. NeoForge: `RegisterParticleProvidersEvent` (mod bus) →
   `event.registerSpriteSet(TYPE.get(), sprites -> (type, level, x, y, z, vx, vy, vz, random) -> new RuneSparkParticle(level, x, y, z, vx, vy, vz, sprites))`.
   In 26.3, `ParticleProvider#createParticle` also takes a trailing `RandomSource`. Fabric:
   `ParticleProviderRegistry.getInstance().register(TYPE, RuneSparkProvider::new)` (check the
   class name in your Fabric API version; older ones call it `ParticleFactoryRegistry`).
4. Particle class: extend `SingleQuadParticle` (or `SimpleAnimatedParticle`). Set `lifetime`,
   `gravity`, `friction`, `hasPhysics`, `quadSize`, and pick a `SingleQuadParticle.Layer`
   (`Layer.bySprite(sprite)` for vanilla atlases; `TRANSLUCENT` for soft glows). Animate in
   `tick()` with `setSpriteFromAge(spriteSet)`, alpha fade-out, and size curves.

```java
public class RuneSparkParticle extends SingleQuadParticle {   // compiles against 26.3
    private final SpriteSet sprites;
    protected RuneSparkParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z, vx, vy, vz, sprites.first());
        this.sprites = sprites;
        this.lifetime = 14 + random.nextInt(8);
        this.gravity = -0.02f;            // float upward
        this.friction = 0.92f;
        this.quadSize *= 0.6f;
        this.hasPhysics = false;
        setSpriteFromAge(sprites);
    }
    @Override public void tick() {
        super.tick();
        setSpriteFromAge(sprites);
        float life = (float) age / lifetime;
        setAlpha(1f - life * life);           // ease-out fade
    }
    @Override protected Layer getLayer() { return Layer.TRANSLUCENT; }
    @Override public int getLightCoords(float pt) { return LightCoordsUtil.FULL_BRIGHT; } // glow in the dark (26.1: getLightColor -> getLightCoords)
}
```

Spawning:
- Server (everyone sees it): `serverLevel.sendParticles(TYPE, x, y, z, count, spreadX, spreadY, spreadZ, speed)`.
- Client only (local cosmetic): `level.addParticle(TYPE, x, y, z, vx, vy, vz)`. Use this for
  per-frame trails in renderers or HUD-adjacent effects.
- Shapes with intent: **ring** (`cos/sin` around the anchor, for shockwave telegraphs),
  **spiral** (angle += t, y += t, for summoning), **burst** (random unit vector × speed,
  for impacts), **line** (lerp from A to B, for beams and tethers), **converge** (spawn on a
  sphere with velocity toward the center, for charging).
- Budget: under ~200 live particles from your mod in a busy scene. Spread spawns over ticks.
  Respect `minecraft.options.particles()`.

## World-space rendering

- Entities and block entities: render through their renderer's `submit(...)` with the
  `SubmitNodeCollector` (`submitModel`, `submitModelPart`, `submitItem`, `submitBlockModel`,
  `submitText`, `submitShadow`, `submitCustomGeometry`, `submitShapeOutline`). Use
  `collector.order(n)` to draw after another feature.
- Arbitrary geometry not tied to an entity (beams, area markers, waypoints):
  - NeoForge: `SubmitCustomGeometryEvent` (game bus). It gives the `SubmitNodeCollector`,
    `PoseStack` and `LevelRenderState`. Translate by `-cameraPos` first (positions are
    camera-relative).
  - NeoForge stage hooks: `RenderLevelStageEvent.AfterSky/AfterOpaqueBlocks/AfterOpaqueFeatures/AfterTranslucentBlocks/AfterTranslucentFeatures/AfterTranslucentParticles/AfterWeather`
    (and `OitTranslucent` when improved transparency is on) for low-level work with the
    active `RenderPass`.
  - Fabric: the world render events in Fabric API (`develop/rendering/world.md` in the Fabric
    docs; names track the 26.x feature pipeline).
- `submitCustomGeometry(poseStack, renderType, (pose, vertexConsumer) -> { ... })` is the
  catch-all: write quads with `addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setLight(light).setNormal(pose, nx, ny, nz)`
  matching the render type's vertex format. Check the exact lambda shape in
  `OrderedSubmitNodeCollector`.
- Billboards (icons over NPCs): rotate the pose by the camera orientation
  (`poseStack.mulPose(cameraState.orientation)`, field name to verify) before emitting the quad.

### World-space lines (verified on 26.3)

```java
@SubscribeEvent // game bus, client
static void world(SubmitCustomGeometryEvent event) {
    Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;           // subtract in double, then cast
    float w = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
    event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.lines(), (pose, buf) -> {
        // per segment: two vertices, normal = segment direction
        buf.addVertex(pose, (float) (ax - cam.x), (float) (ay - cam.y), (float) (az - cam.z))
           .setColor(0xFF7CE07C).setNormal(pose, nx, ny, nz).setLineWidth(w * 2);
        buf.addVertex(pose, (float) (bx - cam.x), (float) (by - cam.y), (float) (bz - cam.z))
           .setColor(0xFF7CE07C).setNormal(pose, nx, ny, nz).setLineWidth(w * 2);
    });
}
```

Boxes: `collector.submitShapeOutline(pose, Shapes.create(aabb), RenderTypes.lines(), argb, width, false)`.

**Always-on-top lines** (map annotations that show through trees and hills): vanilla has no such
type, so build one from the lines snippet with the depth test off, and register the pipeline:

```java
public static final RenderPipeline OVERLAY_LINES_PIPELINE = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(id("pipeline/overlay_lines"))
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))   // 26.x uses reversed-Z; DEFAULT is GREATER_THAN_OR_EQUAL
        .build();
public static final RenderType OVERLAY_LINES = RenderType.create("mymod_overlay_lines",
        RenderSetup.builder(OVERLAY_LINES_PIPELINE).setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).createRenderSetup());
// mod bus, client: RegisterRenderPipelinesEvent -> event.registerPipeline(OVERLAY_LINES_PIPELINE)
```

Surface-following lines: sample `Heightmap.Types.MOTION_BLOCKING_NO_LEAVES` (`WORLD_SURFACE`
includes canopies), and flatten one-column spikes (tree trunks count as ground).

## Custom render pipelines and shaders

- Define `RenderPipeline`s with the builder (`withLocation`, `withVertexShader`,
  `withFragmentShader`, `withVertexBinding(0, DefaultVertexFormat.X)`,
  `withPrimitiveTopology(...)`, `withBindGroupLayout(...)`, `withColorTargetState(...)`,
  `withDepthStencilState(...)`). Base them on a vanilla snippet so the standard uniforms are present.
  - NeoForge: register in `RegisterRenderPipelinesEvent` so they get precompiled.
  - Fabric's compiled example: `reference/latest/src/client/java/com/example/docs/rendering/CustomRenderPipeline.java`
    in `FabricMC/fabric-docs`.
- Shader files: `assets/<ns>/shaders/core/<name>.vsh|.fsh`. 26.3 rules:
  `#extension GL_ARB_separate_shader_objects : require`, `#include <minecraft:fog.glsl>`
  (not `#moj_import`), and `layout(location = N)` on every `in`/`out` (vertex outs must match
  fragment ins by location). Compiled with shaderc for GL and Vulkan, so write portable GLSL.
- Post-processing: post-effect chains are JSON under `assets/<ns>/post_effect/` (format from
  1.21.2: named targets, passes with sampler inputs, `PostChainConfig`). Apply them through
  the game renderer's post-effect hook (check `GameRenderer` for the current method). 26.3
  removed the old `transparency` post effect in favor of OIT.
- Prefer particles, emissive textures, animated textures and overlays over custom shaders.
  Shaders are where version churn hurts most, and they can conflict with Iris/shaderpacks.

## Screen-space juice

| Effect | How |
|---|---|
| Screen shake | NeoForge `ViewportEvent.ComputeCameraAngles` (yaw/pitch/roll offsets from layered sines × decaying strength). Fabric: camera mixin. Scale it with a client config |
| FOV punch (dash, impact) | `ViewportEvent.ComputeFov` / `ComputeFovModifierEvent`: kick +5–10%, ease back over 6–10 ticks |
| Hit-stop | freeze the attacker/victim animation 2–4 ticks (pause your animation time or set GeckoLib controller speed to 0) and add a short shake |
| Vignette / damage flash | full-screen `fill` or a nine-slice vignette sprite in a HUD layer, alpha eased out |
| Fog mood (boss arena, cursed biome) | `ViewportEvent.RenderFog` (near/far planes) + `ComputeFogColor`. Fabric example: `AcidFogEnvironment` in the docs reference code |
| Damage numbers / floating text | client-only: store transient world-text entries and `submitText` them each frame with rise + fade |
| Outline/glow highlight | the vanilla glowing effect (`MobEffects.GLOWING`, team color), or `submitShapeOutline` for blocks |

## Sound design in code

- One-shots: `level.playSound(null, x, y, z, sound, source, volume, pitch)` on the server.
  Positional sounds need mono files.
- Looping/following sounds (engines, magic hum, boss drone): extend
  `AbstractTickableSoundInstance`, update `x/y/z/volume/pitch` in `tick()`, and `stop()` when
  done. Start it client-side from the renderer or a payload.
- Music overrides (boss fight, story area): NeoForge `SelectMusicEvent`. Fabric: mixin on
  `Minecraft#getSituationalMusic` (verify).
- Layering: a big moment combines low (boom/thud) + mid (crack/whoosh) + high (sparkle/tinkle)
  sounds. Randomize pitch 0.9–1.1.

## Performance rules

- Never allocate per vertex or per frame in hot paths. Cache `ItemQuads`, baked models,
  `Identifier`s and render types in static or instance fields.
- Do expensive logic in `extractRenderState` once per frame, not per layer or vertex.
- Cull: skip submissions when `!frustum.isVisible(aabb)` or when the object is farther than
  your effect radius. Use `LevelRenderState`/camera position.
- Batch with `BatchableSubmit#batchKey` in custom features, and keep render-type switches low.
- Profile with F3+L (profiler capture) and the debug renderer. Watch the frame-time graph when
  your effect spawns.

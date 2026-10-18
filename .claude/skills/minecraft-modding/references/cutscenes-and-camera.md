# Cutscenes and Camera Control

An in-game cutscene combines: **camera path** (position/rotation/FOV/roll over time) +
**presentation** (letterbox, fades, subtitles, hidden HUD) + **actors** (entities animating
and moving on cue) + **audio** + **input lock and skip** + **server authority**.

Before building one, consider whether an existing tool covers it:
- **Director's Camera** (NeoForge, 26.1.2 at time of writing): server-driven JSON/KubeJS camera
  paths with shake, timed sounds, hold-to-skip, keyframes.
- **CMDCam**: a smooth camera-path tool (needs CreativeCore). Better for recording than gameplay.
- Trailers rather than gameplay → Replay Mod / Flashback.

Build your own when the cutscene is part of your mod's gameplay (quests trigger it, NPCs act in
it, it has to look bespoke). The design below works on both loaders. Client hooks are shown
for NeoForge, with Fabric notes.

## Architecture

```
data/<ns>/<modid>/cutscenes/tower_reveal.json  ──(server reload listener)──► CutsceneDef
                                                                             │
Server: CutsceneService.play(player, id, anchor) ── PlayCutscene(def, anchor, yaw) ──► Client: CutscenePlayer
  - marks player "in cutscene" (optional invulnerability)                               - samples camera every frame
  - runs ACTOR events on server ticks (triggerAnim, move, spawn)                       - fires CLIENT events (subtitle, sound, fade, shake)
  - timeout = duration + 5 s                                                           - letterbox/hides HUD, locks input
  ◄── CutsceneFinished(id, skipped) ─────────────────────────────────────────────────  - hold [key] to skip
  - runs on-complete (advance quest, teleport, give item)
```

The server sends the **whole definition** in the payload, so designers edit one data-pack
file and `/reload`. Camera keys are **relative to an anchor** (position + yaw), so a cutscene
can play at any instance of a structure.

## Data format

```json5
// data/mymod/mymod/cutscenes/tower_reveal.json
{
  "duration": 9.0,
  "letterbox": true,
  "skippable": true,
  "camera": [   // time in seconds; pos relative to anchor, rotated by anchor yaw
    { "time": 0.0, "pos": [0, 2.0, -3], "yaw": 0,   "pitch": 10, "fov": 70, "ease": "in_out_sine" },
    { "time": 3.5, "pos": [6, 6.0, 8],  "yaw": -30, "pitch": -5, "fov": 60, "ease": "in_out_cubic" },
    { "time": 7.0, "pos": [4, 18.0, 30],"yaw": -10, "pitch": -15,"fov": 50, "roll": 2, "ease": "out_cubic" },
    { "time": 9.0, "pos": [4, 18.5, 31],"yaw": -10, "pitch": -15,"fov": 50 }
  ],
  "events": [
    { "time": 0.0, "type": "fade",     "to": 0.0, "over": 0.8 },
    { "time": 0.5, "type": "subtitle", "text": {"translate": "mymod.cutscene.tower.line1"}, "for": 3.0 },
    { "time": 1.0, "type": "actor",    "action": "trigger_anim", "tag": "elder", "anim": "wave" },
    { "time": 3.4, "type": "sound",    "sound": "mymod:cutscene.whoosh" },
    { "time": 6.8, "type": "shake",    "strength": 0.6, "over": 1.2 },
    { "time": 8.2, "type": "fade",     "to": 1.0, "over": 0.8 }
  ]
}
```

## Shared definition + codecs

```java
public record CameraKey(float time, Vec3 pos, float yaw, float pitch, float roll, float fov, Easing ease) {
    public static final Codec<Easing> EASING = Codec.STRING.xmap(s -> Easing.valueOf(s.toUpperCase(Locale.ROOT)),
            e -> e.name().toLowerCase(Locale.ROOT));
    public static final Codec<CameraKey> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("time").forGetter(CameraKey::time),
            Vec3.CODEC.fieldOf("pos").forGetter(CameraKey::pos),
            Codec.FLOAT.optionalFieldOf("yaw", 0f).forGetter(CameraKey::yaw),
            Codec.FLOAT.optionalFieldOf("pitch", 0f).forGetter(CameraKey::pitch),
            Codec.FLOAT.optionalFieldOf("roll", 0f).forGetter(CameraKey::roll),
            Codec.FLOAT.optionalFieldOf("fov", 70f).forGetter(CameraKey::fov),
            EASING.optionalFieldOf("ease", Easing.IN_OUT_SINE).forGetter(CameraKey::ease)
    ).apply(i, CameraKey::new));
}

public record CutsceneEvent(float time, String type, Optional<Component> text, Optional<Identifier> sound,
                            float to, float over, float strength, Optional<String> tag, Optional<String> action,
                            Optional<String> anim) {
    public static final Codec<CutsceneEvent> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("time").forGetter(CutsceneEvent::time),
            Codec.STRING.fieldOf("type").forGetter(CutsceneEvent::type),
            ComponentSerialization.CODEC.optionalFieldOf("text").forGetter(CutsceneEvent::text),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(CutsceneEvent::sound),
            Codec.FLOAT.optionalFieldOf("to", 0f).forGetter(CutsceneEvent::to),
            Codec.FLOAT.optionalFieldOf("over", 0f).forGetter(CutsceneEvent::over),   // also used as "for" duration
            Codec.FLOAT.optionalFieldOf("strength", 0f).forGetter(CutsceneEvent::strength),
            Codec.STRING.optionalFieldOf("tag").forGetter(CutsceneEvent::tag),
            Codec.STRING.optionalFieldOf("action").forGetter(CutsceneEvent::action),
            Codec.STRING.optionalFieldOf("anim").forGetter(CutsceneEvent::anim)
    ).apply(i, CutsceneEvent::new));
    public boolean serverSide() { return type.equals("actor"); }
}
// RecordCodecBuilder groups max out at 16 fields. Past that, split into a dispatch codec
// (Codec.STRING.dispatch("type", ...)) with one record per event type. That's the cleaner long-term design.

public record CutsceneDef(float duration, boolean letterbox, boolean skippable, List<CameraKey> camera, List<CutsceneEvent> events) {
    public static final Codec<CutsceneDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("duration").forGetter(CutsceneDef::duration),
            Codec.BOOL.optionalFieldOf("letterbox", true).forGetter(CutsceneDef::letterbox),
            Codec.BOOL.optionalFieldOf("skippable", true).forGetter(CutsceneDef::skippable),
            CameraKey.CODEC.listOf().fieldOf("camera").forGetter(CutsceneDef::camera),
            CutsceneEvent.CODEC.listOf().optionalFieldOf("events", List.of()).forGetter(CutsceneDef::events)
    ).apply(i, CutsceneDef::new));
    // Network: reuse the JSON codec (registry-aware so Components serialize properly)
    public static final StreamCodec<RegistryFriendlyByteBuf, CutsceneDef> STREAM_CODEC =
            ByteBufCodecs.fromCodecWithRegistries(CODEC);
}

public record PlayCutscene(Identifier id, CutsceneDef def, Vec3 anchor, float anchorYaw) implements CustomPacketPayload {
    public static final Type<PlayCutscene> TYPE = new Type<>(id("play_cutscene"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayCutscene> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, PlayCutscene::id, CutsceneDef.STREAM_CODEC, PlayCutscene::def,
            Vec3.STREAM_CODEC, PlayCutscene::anchor, ByteBufCodecs.FLOAT, PlayCutscene::anchorYaw, PlayCutscene::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
public record CutsceneFinished(Identifier id, boolean skipped) implements CustomPacketPayload { /* same pattern */ }
```

`Easing` is the enum from `ui-screens-hud.md`. Put it in common code (no client imports) so
both sides can decode it.

## Camera path math (client)

```java
public record CameraPose(Vec3 pos, float yaw, float pitch, float roll, float fov) {}

static CameraPose sample(List<CameraKey> keys, float t, Vec3 anchor, float anchorYaw) {
    int i = 0;                                             // before the first key, local clamps to 0
    while (i < keys.size() - 2 && t > keys.get(i + 1).time()) i++;
    CameraKey a = keys.get(i), b = keys.get(Math.min(i + 1, keys.size() - 1));
    float local = b.time() == a.time() ? 1f : Mth.clamp((t - a.time()) / (b.time() - a.time()), 0f, 1f);
    float e = a.ease().apply(local);                       // easing per segment, owned by the segment start
    // Catmull-Rom through neighbours for smooth position curves (no corners at keys)
    Vec3 p0 = keys.get(Math.max(i - 1, 0)).pos(), p1 = a.pos(), p2 = b.pos(),
         p3 = keys.get(Math.min(i + 2, keys.size() - 1)).pos();
    Vec3 local3 = catmullRom(p0, p1, p2, p3, e);
    float yaw = Mth.rotLerp(e, a.yaw(), b.yaw());          // shortest-arc yaw
    float pitch = Mth.lerp(e, a.pitch(), b.pitch());
    float roll = Mth.lerp(e, a.roll(), b.roll());
    float fov = Mth.lerp(e, a.fov(), b.fov());
    Vec3 world = anchor.add(local3.yRot(-anchorYaw * Mth.DEG_TO_RAD));   // rotate offset by anchor yaw
    return new CameraPose(world, yaw + anchorYaw, pitch, roll, fov);
}

static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
    double t2 = t * t, t3 = t2 * t;
    return new Vec3(
        0.5 * ((2 * p1.x) + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3),
        0.5 * ((2 * p1.y) + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3),
        0.5 * ((2 * p1.z) + (-p0.z + p2.z) * t + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3));
}
```

Check the sign
convention of `Vec3#yRot` against Minecraft's yaw (0 = south, +90 = west) in game with a
debug cutscene. A wrong sign mirrors the path.

**Easing per segment, Catmull-Rom across segments** gives smooth paths with art-directable
pacing. For "hold then whip" shots, add a duplicate key (same pose, later time).

## Client player (timeline + events)

```java
public final class CutscenePlayer {
    private static PlayCutscene active;
    private static long startTick; private static int nextEvent;
    private static float fade, fadeFrom, fadeTo, fadeStart, fadeOver;   // 0 = clear, 1 = black
    private static float shakeStrength, shakeStart, shakeOver;
    private static Component subtitle; private static float subtitleUntil;
    private static int skipHeldTicks;

    public static void start(PlayCutscene p) {
        active = p; nextEvent = 0; skipHeldTicks = 0; subtitle = null;
        startTick = Minecraft.getInstance().level.getGameTime();
    }
    public static boolean isActive() { return active != null; }
    public static @Nullable Component subtitle() {
        return active != null && subtitle != null && time(0) < subtitleUntil ? subtitle : null;
    }

    /** Seconds since start, interpolated between ticks; follows game time (freezes when paused). */
    public static float time(float partialTick) {
        return (Minecraft.getInstance().level.getGameTime() - startTick + partialTick) / 20f;
    }

    public static CameraPose pose(float partialTick) {
        CameraPose p = sample(active.def().camera(), time(partialTick), active.anchor(), active.anchorYaw());
        float shake = shakeAmount(time(partialTick));
        if (shake > 0) {   // smooth noise, not white noise: layered sines read as camera handling
            float t = time(partialTick) * 25f;
            p = new CameraPose(p.pos(), p.yaw() + shake * (Mth.sin(t) * 0.6f + Mth.sin(t * 2.3f) * 0.4f),
                    p.pitch() + shake * Mth.cos(t * 1.7f) * 0.5f, p.roll() + shake * Mth.sin(t * 0.9f) * 0.8f, p.fov());
        }
        return p;
    }

    public static void clientTick() {            // call from ClientTickEvent.Post / END_CLIENT_TICK
        if (active == null) return;
        Minecraft mc = Minecraft.getInstance();
        float now = time(0);
        List<CutsceneEvent> events = active.def().events();
        while (nextEvent < events.size() && events.get(nextEvent).time() <= now) fire(events.get(nextEvent++), now);
        if (active.def().skippable() && ModKeys.SKIP_CUTSCENE.isDown()) {
            if (++skipHeldTicks >= 20) finish(true);      // hold 1 s to skip; draw a radial progress in the HUD
        } else skipHeldTicks = 0;
        if (active != null && now >= active.def().duration()) finish(false);
    }

    private static void fire(CutsceneEvent e, float now) {
        Minecraft mc = Minecraft.getInstance();
        switch (e.type()) {
            case "subtitle" -> { subtitle = e.text().orElse(Component.empty()); subtitleUntil = e.time() + e.over(); }
            case "sound" -> e.sound().ifPresent(id -> BuiltInRegistries.SOUND_EVENT.get(id).ifPresent(h ->
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(h.value(), 1f))));  // verify Registry#get return type
            case "fade" -> { fadeFrom = fade; fadeTo = e.to(); fadeStart = e.time(); fadeOver = Math.max(e.over(), 0.01f); }
            case "shake" -> { shakeStrength = e.strength() * ModClientConfig.shakeScale(); shakeStart = e.time(); shakeOver = e.over(); }
            default -> {}   // "actor" events run on the server
        }
    }

    public static float fadeAlpha(float partialTick) {
        float t = time(partialTick);
        float k = Mth.clamp((t - fadeStart) / fadeOver, 0f, 1f);
        return fade = Mth.lerp(Easing.IN_OUT_SINE.apply(k), fadeFrom, fadeTo);
    }
    private static float shakeAmount(float t) {
        float k = (t - shakeStart) / Math.max(shakeOver, 0.01f);
        return k < 0 || k > 1 ? 0 : shakeStrength * (1 - k) * (1 - k);        // decays quadratically
    }
    public static float letterbox(float partialTick) {                         // 0..1 bar progress
        if (active == null || !active.def().letterbox()) return 0;
        float t = time(partialTick), d = active.def().duration();
        return Easing.OUT_CUBIC.apply(Math.min(Mth.clamp(t / 0.6f, 0, 1), Mth.clamp((d - t) / 0.6f, 0, 1)));
    }

    public static void finish(boolean skipped) {
        ClientPacketDistributor.sendToServer(new CutsceneFinished(active.id(), skipped)); // Fabric: ClientPlayNetworking.send
        active = null; subtitle = null; fade = 0;
    }
    public static void onDisconnect() { active = null; }   // ClientPlayerNetworkEvent.LoggingOut
}
```

## Hooking the camera

### Position + rotation + detach (mixin, both loaders)

The camera normally sits on the camera entity's interpolated eye position. Override it at the
end of `Camera#update` (renamed from `setup` in 26.1). Mark the camera `detached` so the local
player's body renders.

```java
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPosition(Vec3 pos);
    @Shadow protected abstract void setRotation(float yRot, float xRot, float roll); // NeoForge-patched overload
    // Fabric/vanilla: @Shadow protected abstract void setRotation(float yRot, float xRot);
    @Shadow private boolean detached;

    // Handler takes only CallbackInfo, so it doesn't depend on update()'s parameter list.
    // If 'update' doesn't resolve, open Camera in the decompiled sources and target the method that calls setPosition.
    @Inject(method = "update", at = @At("TAIL"))
    private void mymod$applyCutscene(CallbackInfo ci) {
        if (!CutscenePlayer.isActive()) return;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        CameraPose pose = CutscenePlayer.pose(pt);
        this.setRotation(pose.yaw(), pose.pitch(), pose.roll());
        this.setPosition(pose.pos());
        this.detached = true;
    }
}
```

### FOV

- NeoForge: `ViewportEvent.ComputeFov` (game bus): `if (CutscenePlayer.isActive()) event.setFOV(CutscenePlayer.pose(pt).fov());`
- Fabric: `@ModifyReturnValue` (MixinExtras) on `Camera#getFov` (added in 26.1). Check its
  exact name and signature in the sources.

### Roll and shake without a mixin (NeoForge, gameplay camera)

`ViewportEvent.ComputeCameraAngles` exposes yaw/pitch/**roll** for the *normal* camera: good
for screen shake on explosions, dizziness and boat sway outside cutscenes. In third person,
`CalculateDetachedCameraDistanceEvent` changes zoom distance (use it for a pulled-back boss
camera).

### Alternative: camera entity

`Minecraft.getInstance().setCameraEntity(entity)` renders from another entity (how spectator
mode works). Spawn a client-only marker entity, move it every frame (set both `xo/yo/zo` and
position to the sampled pose to avoid tick-lerp lag), and restore the player afterwards. It
needs no camera mixin, but it fights the interpolation, and FOV/roll still need hooks. Prefer
the mixin.

## Presentation layer (HUD)

```java
// NeoForge RegisterGuiLayersEvent: e.registerAboveAll(id("cutscene"), CutsceneHud::extract);
public static void extract(GuiGraphicsExtractor g, DeltaTracker dt) {
    if (!CutscenePlayer.isActive()) return;
    float pt = dt.getGameTimeDeltaPartialTick(false);
    int w = g.guiWidth(), h = g.guiHeight();
    int bar = Math.round(h * 0.12f * CutscenePlayer.letterbox(pt));        // 2.35:1-ish bars
    g.fill(0, 0, w, bar, 0xFF000000);
    g.fill(0, h - bar, w, h, 0xFF000000);
    Component line = CutscenePlayer.subtitle();
    if (line != null) {                                                    // subtitle centered in lower bar
        Font font = Minecraft.getInstance().font;
        g.centeredText(font, line, w / 2, h - bar / 2 - font.lineHeight / 2, 0xFFFFFFFF);
    }
    int a = Math.round(CutscenePlayer.fadeAlpha(pt) * 255);
    if (a > 0) g.fill(0, 0, w, h, a << 24);                               // fade to/from black
    // optional: hold-to-skip hint + radial progress in the bottom-right corner
}

// Hide vanilla HUD while active (NeoForge, game bus):
@SubscribeEvent static void hideHud(RenderGuiLayerEvent.Pre e) {
    if (CutscenePlayer.isActive() && !e.getName().equals(id("cutscene"))) e.setCanceled(true);
}
// Fabric: wrap the elements you hide via HudElementRegistry.replaceElement(id, old -> (g, dt) -> { if (!active) old.extractRenderState(g, dt); })
// (the HudElement method name tracks the 26.x extract rename; check the interface)
```

Also hide the hand: NeoForge `RenderHandEvent` → cancel. Fabric: a mixin on the hand renderer,
or set `minecraft.options.hideGui` temporarily (and restore it).

## Input lock

- **Movement**: cancel `KeyboardInput#tick` at HEAD when active, after setting
  `keyPresses = Input.EMPTY` and zeroing the protected `moveVector` via `@Shadow`. (NeoForge's
  `MovementInputUpdateEvent` fires after `moveVector` is computed, so it isn't enough by itself.)
- **Mouse look**: cancel `MouseHandler#turnPlayer` at HEAD when active, or NeoForge
  `CalculatePlayerTurnEvent` → `setMouseSensitivity(...)` to dampen.
- **Attacks/use/hotbar**: NeoForge `InputEvent.InteractionKeyMappingTriggered` → cancel +
  `setSwingHand(false)`. Fabric: mixin on `Minecraft#handleKeybinds`.
- Escape still opens the pause menu, which is fine because `time()` follows game time and freezes.

## Server side

```java
public final class CutsceneService {
    private record Running(Identifier id, CutsceneDef def, Vec3 anchor, float yaw, long startTick, Runnable onDone) {}
    private static final Map<UUID, Running> RUNNING = new HashMap<>();

    public static void play(ServerPlayer p, Identifier id, Vec3 anchor, float yaw, Runnable onDone) {
        CutsceneDef def = CutsceneManager.get(p.level().getServer(), id).orElseThrow();
        RUNNING.put(p.getUUID(), new Running(id, def, anchor, yaw, p.level().getGameTime(), onDone));
        PacketDistributor.sendToPlayer(p, new PlayCutscene(id, def, anchor, yaw));
    }

    public static void tick(MinecraftServer server) {              // ServerTickEvent.Post
        RUNNING.entrySet().removeIf(entry -> {
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            Running r = entry.getValue();
            if (p == null) return true;
            float t = (p.level().getGameTime() - r.startTick()) / 20f;
            for (CutsceneEvent e : r.def().events())           // actor events whose time falls in this tick
                if (e.serverSide() && e.time() > t - 0.05f && e.time() <= t) runActor(p, r, e);
            if (t > r.def().duration() + 5f) { r.onDone().run(); return true; }   // client never answered
            return false;
        });
    }

    public static void onFinished(CutsceneFinished msg, ServerPlayer p) {
        Running r = RUNNING.get(p.getUUID());
        if (r != null && r.id().equals(msg.id())) { RUNNING.remove(p.getUUID()); r.onDone().run(); }
    }
    public static boolean inCutscene(Player p) { return RUNNING.containsKey(p.getUUID()); }

    private static void runActor(ServerPlayer p, Running r, CutsceneEvent e) {
        // Find actors near the anchor by scoreboard tag, then act
        AABB box = AABB.ofSize(r.anchor(), 64, 32, 64);
        for (Entity actor : p.level().getEntities((Entity) null, box, ent -> e.tag().map(ent.getTags()::contains).orElse(false))) {
            switch (e.action().orElse("")) {
                case "trigger_anim" -> { if (actor instanceof GeoEntity geo) geo.triggerAnim(null, e.anim().orElseThrow()); }
                case "look_at_player" -> { if (actor instanceof Mob m) m.getLookControl().setLookAt(p); }
                default -> {}
            }
        }
    }
}
```

Practical rules:
- **Chunks**: the client only has chunks near the player. Keep camera paths within render
  distance of the player's position. For a far "reveal" shot, teleport the player first
  (behind a fade) or send them to spectate a server-side marker entity.
- **Invulnerability**: while `inCutscene`, cancel incoming damage (NeoForge
  `LivingIncomingDamageEvent`, Fabric `ServerLivingEntityEvents.ALLOW_DAMAGE`). Despawn or
  freeze hostiles near the anchor, or play the cutscene in a safe area.
- **Multiplayer**: others see a frozen player. For group scenes send `PlayCutscene` to every
  participant with the same anchor and start tick (`sendToPlayersNear`).
- **Once only**: record completion in a player attachment (`seenCutscenes` set) and offer a
  replay item or command.
- **Comfort**: a config to skip all cutscenes, reduce shake, and disable FOV/roll. Never
  roll more than about 5° and keep FOV changes slow (≥ 1 s).

## Shot-craft cheat sheet

- **Establishing shot** (wide, slow push-in, 3–5 s) → **medium** on the speaker → **close-up**
  for the reveal line → **wide** for the payoff.
- Camera moves ease in and out. Cut on action (an impact, a door opening) to hide transitions.
  A 6–10 tick fade-through-black covers big position jumps.
- Lead the subject: leave space in the frame in the direction the subject faces or moves.
- 180° rule: in a dialogue between two NPCs, keep the camera on one side of the line between them.
- Sync sound on cuts. A whoosh into the reveal and a low drone under the establishing shot add
  a lot of polish cheaply.
- Keep the first cutscene of a mod short (≤ 10 s) and skippable.

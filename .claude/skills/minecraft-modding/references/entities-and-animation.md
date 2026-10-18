# Entities and Animation

Three ways to animate, from lightest to heaviest:

| Approach | Use when | Notes |
|---|---|---|
| **Vanilla keyframes** (`AnimationDefinition` → `KeyframeAnimation`) | Vanilla-style mobs, few animations, no extra dependency | Code-defined, or JSON on NeoForge (`assets/<ns>/neoforge/animations/entity/*.json`) |
| **GeckoLib 5** | Complex rigs, many animations, blending/transitions, sound/particle keyframes, Molang, armor/items/blocks | Blockbench → `.geo.json` + `.animation.json`. Adds a dependency |
| **Procedural** (`setupAnim` math) | Breathing, head tracking, tails, wings, IK-ish leg placement | Layer it on top of either system above |

For **player** animations (emotes, casting poses, cutscene acting), use **Player Animation
Library** (ZigyTheBird, Fabric + NeoForge, 1.2.x for 26.x). It plays Blockbench/GeckoLib/Bedrock
JSON animations on the player model with custom pivots.

## The entity (server side)

```java
public class ElderNpc extends PathfinderMob {
    // Synced flag -> drives a client AnimationState
    private static final EntityDataAccessor<Boolean> TALKING =
            SynchedEntityData.defineId(ElderNpc.class, EntityDataSerializers.BOOLEAN);
    public final AnimationState talkAnimationState = new AnimationState(); // client-side use
    public final AnimationState waveAnimationState = new AnimationState();

    public ElderNpc(EntityType<? extends ElderNpc> type, Level level) { super(type, level); }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 40).add(Attributes.MOVEMENT_SPEED, 0.22);
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8f));
        goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(TALKING, false);
    }
    public void setTalking(boolean v) { entityData.set(TALKING, v); }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (TALKING.equals(key)) talkAnimationState.animateWhen(entityData.get(TALKING), tickCount);
    }

    // One-shot animations: broadcast an entity event byte from the server
    public void playWave() { level().broadcastEntityEvent(this, (byte) 64); }
    @Override public void handleEntityEvent(byte id) {
        if (id == 64) waveAnimationState.start(tickCount); else super.handleEntityEvent(id);
    }

    @Override protected void addAdditionalSaveData(ValueOutput out) { super.addAdditionalSaveData(out); }
    @Override protected void readAdditionalSaveData(ValueInput in) { super.readAdditionalSaveData(in); }
}
```

Two sync patterns:
- **Looping or stateful** (talking, sitting, charging): a `SynchedEntityData` flag, plus
  `animateWhen` in `onSyncedDataUpdated`.
- **One-shot** (wave, attack, roar): `level().broadcastEntityEvent(this, byte)` →
  `handleEntityEvent` → `state.start(tickCount)`. Use ids above vanilla's range (vanilla uses
  up to about 63), and check the parent class doesn't already use yours.

`ValueOutput`/`ValueInput` replaced raw NBT for entity saving (1.21.6+): `out.putInt("k", v)` /
`in.getInt("k").orElse(0)`, and `out.store("k", codec, value)` / `in.read("k", codec)` for codec data.

## Vanilla model + keyframes (client)

```java
public class ElderRenderState extends LivingEntityRenderState {
    public final AnimationState talk = new AnimationState();
    public final AnimationState wave = new AnimationState();
}

public class ElderModel extends EntityModel<ElderRenderState> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(id("elder"), "main");
    private final ModelPart head, body, rightArm, leftArm;
    private final KeyframeAnimation talk, wave;

    public ElderModel(ModelPart root) {
        super(root);
        head = root.getChild("head"); body = root.getChild("body");
        rightArm = root.getChild("right_arm"); leftArm = root.getChild("left_arm");
        talk = ElderAnimations.TALK.bake(root);   // bake once, here
        wave = ElderAnimations.WAVE.bake(root);
    }

    public static LayerDefinition createBodyLayer() {   // or Blockbench "Export Java Entity"
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4, -8, -4, 8, 8, 8)
                .texOffs(32, 0).addBox(-4, -8, -4, 8, 8, 8, new CubeDeformation(0.5f)), // hat/hood overlay
                PartPose.offset(0, 0, 0));
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 16).addBox(-4, 0, -2, 8, 12, 4), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16).addBox(-3, -2, -2, 4, 12, 4), PartPose.offset(-5, 2, 0));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(40, 16).mirror().addBox(-1, -2, -2, 4, 12, 4), PartPose.offset(5, 2, 0));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4), PartPose.offset(-1.9f, 12, 0));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(0, 16).mirror().addBox(-2, 0, -2, 4, 12, 4), PartPose.offset(1.9f, 12, 0));
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override public void setupAnim(ElderRenderState s) {
        super.setupAnim(s);                              // resets every part to its default pose
        // Procedural layer: head tracking + idle breathing (ageInTicks already includes partialTick)
        head.yRot = s.yRot * Mth.DEG_TO_RAD;
        head.xRot = s.xRot * Mth.DEG_TO_RAD;
        float breathe = Mth.sin(s.ageInTicks * 0.08f);
        body.y += breathe * 0.15f;
        rightArm.zRot = 0.05f + breathe * 0.03f; leftArm.zRot = -rightArm.zRot;
        // Keyframe layers on top
        talk.apply(s.talk, s.ageInTicks);
        wave.apply(s.wave, s.ageInTicks);
        // Walk cycle helper for a looping walk animation definition:
        // walk.applyWalk(s.walkAnimationPos, s.walkAnimationSpeed, 2f, 2.5f);
    }
}

public final class ElderAnimations {
    public static final AnimationDefinition WAVE = AnimationDefinition.Builder.withLength(1.2f)
        .addAnimation("right_arm", new AnimationChannel(AnimationChannel.Targets.ROTATION,
            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(0.25f, KeyframeAnimations.degreeVec(0, 0, 150), AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(0.45f, KeyframeAnimations.degreeVec(0, 0, 125), AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(0.65f, KeyframeAnimations.degreeVec(0, 0, 150), AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(0.85f, KeyframeAnimations.degreeVec(0, 0, 125), AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(1.2f, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.CATMULLROM)))
        .build();
    public static final AnimationDefinition TALK = AnimationDefinition.Builder.withLength(0.6f).looping()
        .addAnimation("head", new AnimationChannel(AnimationChannel.Targets.ROTATION,
            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(0.3f, KeyframeAnimations.degreeVec(-6, 0, 2), AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(0.6f, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.CATMULLROM)))
        .build();
}

public class ElderRenderer extends MobRenderer<ElderNpc, ElderRenderState, ElderModel> {
    private static final Identifier TEXTURE = id("textures/entity/elder.png");
    public ElderRenderer(EntityRendererProvider.Context ctx) { super(ctx, new ElderModel(ctx.bakeLayer(ElderModel.LAYER)), 0.5f); }
    @Override public ElderRenderState createRenderState() { return new ElderRenderState(); }
    @Override public void extractRenderState(ElderNpc e, ElderRenderState s, float partialTick) {
        super.extractRenderState(e, s, partialTick);
        s.talk.copyFrom(e.talkAnimationState);
        s.wave.copyFrom(e.waveAnimationState);
    }
    @Override public Identifier getTextureLocation(ElderRenderState s) { return TEXTURE; }
}
```

Registration:
- NeoForge (mod bus, client): `EntityRenderersEvent.RegisterLayerDefinitions` →
  `event.registerLayerDefinition(ElderModel.LAYER, ElderModel::createBodyLayer)` (the 26.x docs say `add`; the 26.3 code says `registerLayerDefinition`). `EntityRenderersEvent.RegisterRenderers` →
  `event.registerEntityRenderer(ELDER.get(), ElderRenderer::new)`.
- Fabric (client initializer): `ModelLayerRegistry.registerModelLayer(ElderModel.LAYER, ElderModel::createBodyLayer)`
  and `EntityRenderers.register(ELDER, ElderRenderer::new)`.

Notes:
- Rotations in `ModelPart` are **radians**; `degreeVec` handles conversion in keyframes. The Y
  axis is flipped in model space (negative is up).
- Never read the entity inside `setupAnim`/`submit`. Copy everything into the render state in
  `extractRenderState`.
- To add data to *existing* renderers without subclassing (NeoForge):
  `RegisterRenderStateModifiersEvent` + `ContextKey`, then `state.getRenderData(KEY)`.

### NeoForge JSON keyframe animations (designer-friendly)

`assets/<ns>/neoforge/animations/entity/elder_wave.json`:

```json5
{ "length": 1.2, "loop": false,
  "animations": [ { "bone": "right_arm", "target": "minecraft:rotation",
    "keyframes": [ {"timestamp": 0.0, "target": [0,0,0],   "interpolation": "minecraft:catmullrom"},
                   {"timestamp": 0.25,"target": [0,0,150], "interpolation": "minecraft:catmullrom"},
                   {"timestamp": 1.2, "target": [0,0,0],   "interpolation": "minecraft:catmullrom"} ] } ] }
```

```java
public static final AnimationHolder WAVE = Model.getAnimation(id("elder_wave"));
this.wave = WAVE.get().bake(root);   // in the model constructor
```

Targets: `minecraft:position|rotation|scale`. Interpolations: `minecraft:linear|catmullrom`.
Add more with `RegisterJsonAnimationTypesEvent` (e.g. an ease-out-back interpolation). The
Blockbench "Animation to JSON" plugin exports this format.

## GeckoLib 5 (26.x)

Package `com.geckolib.*`. Assets:
```
assets/<ns>/geckolib/models/entity/elder.geo.json
assets/<ns>/geckolib/animations/entity/elder.animation.json
assets/<ns>/textures/entity/elder.png
```
In Blockbench, use the "GeckoLib Animated Model" project type (from the GeckoLib plugin) and
export the model and animations into those folders.

```java
public class ElderNpc extends PathfinderMob implements GeoEntity {
    private static final RawAnimation IDLE  = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK  = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation WAVE  = RawAnimation.begin().thenPlay("wave");
    private static final RawAnimation BOW_THEN_IDLE = RawAnimation.begin().thenPlay("bow").thenLoop("idle");
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>("base", 5,   // 5-tick blend between animations
                test -> test.setAndContinue(test.isMoving() ? WALK : IDLE)));
        controllers.add(new AnimationController<ElderNpc>("gesture", test -> PlayState.STOP)
                .triggerableAnim("wave", WAVE).triggerableAnim("bow", BOW_THEN_IDLE)
                .setSoundKeyframeHandler(e -> { /* sound keyframe reached: play it client-side (see KeyFrameEvent/SoundKeyframeData) */ }));
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    // Server side: syncs automatically to tracking clients
    public void greet() { triggerAnim("gesture", "wave"); }
}

// Client registration (NeoForge RegisterRenderers / Fabric EntityRenderers.register):
event.registerEntityRenderer(ModRegistries.ELDER.get(), ctx -> new GeoEntityRenderer<>(ctx, ModRegistries.ELDER.get()));
// The EntityType constructor uses DefaultedEntityGeoModel named after the entity id.
// Custom paths: new GeoEntityRenderer<>(ctx, new DefaultedEntityGeoModel<>(id("npc/elder")))
```

GeckoLib power features: `DefaultAnimations.genericWalkIdleController()` and other ready-made
controllers; `additiveAnimations()` controllers that layer a gesture on top of locomotion;
`setAnimationSpeed`; keyframe handlers for sound, particles and custom instructions (footstep
dust, swing sounds exactly on the frame); Molang expressions in animations (`query.anim_time`,
`query.ground_speed`) for procedural motion; render layers (`AutoGlowingGeoLayer` for
`_glowmask` emissives, item-in-hand layers); `GeoArmorRenderer`, `GeoItemRenderer`,
`GeoBlockRenderer` for animated armor, items and blocks. Custom per-frame data goes through
`DataTicket`s added in the renderer's `addRenderData` and read in the controller via
`test.getData(ticket)`.

Rule: a controller handler runs on the client every frame, so keep it cheap and side-effect
free. Gameplay state comes from synced entity data.

## Making animations look professional

- **Timing over keyframe count.** Few, well-timed keys with Catmull-Rom (or GeckoLib easings)
  beat dense linear keys.
- **Anticipation → action → follow-through**: wind back 10–20% before a swing, overshoot past
  the end pose, then settle. On attacks, put the damage frame at the end of the fast part.
- **Overlapping action**: offset child bones (forearm, hand, tail tip, ears) by 1–3 frames
  behind their parent and give them extra amplitude.
- **Arcs**: limbs travel in arcs. Rotate rather than translate wherever possible.
- **Idle never freezes**: add breathing (scale or y offset at 0.05–0.1 Hz), occasional blinks
  (swap a texture or scale an eyelid bone), weight shifts.
- **Head leads**: the head turns before the body. Clamp head yaw to about ±50° and let the body
  follow.
- **Blend**: 3–6 tick transitions between locomotion states. Snapping looks cheap.
- **Speed sync**: scale walk-cycle playback by actual movement speed to avoid foot sliding
  (`walkAnimationSpeed` vanilla, `setControllerSpeed` GeckoLib).
- **Sound and particles on contact frames**: footsteps, landings, impacts.
- **Hitboxes and pose**: keep `EntityDimensions` in sync with big pose changes (crouch, curl up)
  via `getDefaultDimensions(Pose)`/`refreshDimensions()`.

## AI quick reference

- `goalSelector` (behavior) and `targetSelector` (who to attack) with priorities. Common goals:
  `FloatGoal`, `MeleeAttackGoal`, `RangedAttackGoal`, `WaterAvoidingRandomStrollGoal`,
  `LookAtPlayerGoal`, `TemptGoal`, `PanicGoal`, `MoveTowardsRestrictionGoal`,
  `NearestAttackableTargetGoal`, `HurtByTargetGoal`.
- Custom `Goal`: implement `canUse`, `canContinueToUse`, `start`, `stop`, `tick`, and set
  `setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK))` so goals don't fight over control.
- **Brain** (villager/piglin style: memories, sensors, activities) suits rich NPC schedules but
  is heavier. The 26.1 primer documents its changes under "Activities and Brains".
- Navigation: `getNavigation().moveTo(x, y, z, speed)`. Look: `getLookControl().setLookAt(entity, 30, 30)`.
- Bosses: `ServerBossEvent` for the boss bar, phases driven by health thresholds, and telegraphed
  attacks (wind-up animation + particle ring + sound) before damage.

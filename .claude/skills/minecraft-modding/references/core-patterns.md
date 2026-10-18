# Core Patterns: registration, events, data, networking

Examples use Mojang names for 26.x. `MODID` is your mod id; `id(path)` stands for
`Identifier.fromNamespaceAndPath(MODID, path)`.

## Registration

### NeoForge: DeferredRegister

```java
public final class ModRegistries {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(MODID);
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MODID);

    // Item/Block helpers set the registry id on the Properties for you (required since 1.21.2)
    public static final DeferredBlock<Block> RUNE_STONE = BLOCKS.registerSimpleBlock("rune_stone",
            p -> p.mapColor(MapColor.STONE).strength(1.5f).lightLevel(s -> 7));
    public static final DeferredItem<BlockItem> RUNE_STONE_ITEM = ITEMS.registerSimpleBlockItem("rune_stone", RUNE_STONE);
    public static final DeferredItem<Item> QUEST_SCROLL = ITEMS.registerItem("quest_scroll",
            QuestScrollItem::new, p -> p.stacksTo(1).rarity(Rarity.UNCOMMON));

    public static final Supplier<EntityType<ElderNpc>> ELDER = ENTITIES.registerEntityType(
            "elder", ElderNpc::new, MobCategory.MISC, b -> b.sized(0.6f, 1.95f).clientTrackingRange(10));

    public static final DeferredHolder<SoundEvent, SoundEvent> VOICE_BLIP =
            SOUNDS.register("voice_blip", SoundEvent::createVariableRangeEvent);

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus); ITEMS.register(modBus); ENTITIES.register(modBus);
        COMPONENTS.register(modBus); SOUNDS.register(modBus); PARTICLES.register(modBus);
        ATTACHMENTS.register(modBus);
    }
}
```

`registerEntityType(name, factory, category, UnaryOperator<Builder>)` exists in 26.3 (verified). The
long form, if you need it:
`ENTITIES.register(name, () -> EntityType.Builder.of(ElderNpc::new, MobCategory.MISC).sized(..).build(ResourceKey.create(Registries.ENTITY_TYPE, id(name))))`.

Default attributes for living entities go on the mod bus:

```java
@SubscribeEvent
static void attributes(EntityAttributeCreationEvent e) {
    e.put(ModRegistries.ELDER.get(), PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 40).add(Attributes.MOVEMENT_SPEED, 0.25).build());
}
```

Creative tabs: register a `CreativeModeTab` via `DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID)`,
or add to vanilla tabs in `BuildCreativeModeTabContentsEvent`.

### Fabric: register directly in the initializer

```java
public static final ResourceKey<EntityType<?>> ELDER_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("elder"));
public static final EntityType<ElderNpc> ELDER = Registry.register(BuiltInRegistries.ENTITY_TYPE, ELDER_KEY,
        EntityType.Builder.<ElderNpc>of(ElderNpc::new, MobCategory.MISC).sized(0.6f, 1.95f).build(ELDER_KEY));

// Items: Item.Properties must carry the id -> .setId(ResourceKey.create(Registries.ITEM, id))
public static Item register(String name, Function<Item.Properties, Item> f, Item.Properties p) {
    ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
    return Registry.register(BuiltInRegistries.ITEM, key, f.apply(p.setId(key)));
}
// Attributes:
FabricDefaultAttributeRegistry.register(ELDER, ElderNpc.createAttributes());
```

Static registration classes must be class-loaded from `onInitialize()` (call an empty
`init()` on them).

## Events

- **NeoForge**: `@SubscribeEvent` methods; `@EventBusSubscriber(modid=MODID)` for static
  handlers. Lifecycle and registration events are on the mod bus; gameplay events
  (`PlayerInteractEvent`, `LivingDamageEvent`, `ServerTickEvent.Post`, `PlayerEvent.PlayerLoggedInEvent`,
  `RegisterCommandsEvent`) are on `NeoForge.EVENT_BUS`. Cancellable events implement
  `ICancellableEvent` (`event.setCanceled(true)`).
- **Fabric**: callback interfaces (`ServerTickEvents.END_SERVER_TICK.register(server -> ...)`,
  `UseEntityCallback.EVENT`, `ServerPlayConnectionEvents.JOIN`, `ClientTickEvents.END_CLIENT_TICK`,
  `CommandRegistrationCallback.EVENT`, `ServerLifecycleEvents`). Where there's no event, write a
  mixin.

Tick work: do it in `Post`/`END` handlers, guard with `level.isClientSide()`, and throttle with
`if (server.getTickCount() % 20 == 0)`.

## Storing data

| Data lives on | Use |
|---|---|
| An item stack | **Data component** (`DataComponentType<T>` with `persistent(codec)` + `networkSynchronized(streamCodec)`). Never mutate a component value in place: components must be immutable records |
| A player, mob, chunk, level or block entity | **NeoForge data attachments** / **Fabric Data Attachment API** |
| One entity class you own | Fields + `addAdditionalSaveData(ValueOutput)`/`readAdditionalSaveData(ValueInput)`, `SynchedEntityData` for client-visible state |
| The world/server globally (factions, world events, quest boards) | `SavedData` with a `SavedDataType` |
| Designer-authored content (dialogue, quests, cutscenes) | JSON in a data pack, loaded with a reload listener or a datapack registry |

### Data components

```java
public record QuestRef(Identifier quest, int stage) {
    public static final Codec<QuestRef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("quest").forGetter(QuestRef::quest),
            Codec.INT.optionalFieldOf("stage", 0).forGetter(QuestRef::stage)
    ).apply(i, QuestRef::new));
    public static final StreamCodec<ByteBuf, QuestRef> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, QuestRef::quest, ByteBufCodecs.VAR_INT, QuestRef::stage, QuestRef::new);
}

public static final Supplier<DataComponentType<QuestRef>> QUEST_REF = COMPONENTS.registerComponentType(
        "quest_ref", b -> b.persistent(QuestRef.CODEC).networkSynchronized(QuestRef.STREAM_CODEC));

stack.set(QUEST_REF.get(), new QuestRef(id("lost_tower"), 0));
QuestRef ref = stack.get(QUEST_REF.get()); // nullable
```

Fabric: `Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("quest_ref"), DataComponentType.<QuestRef>builder().persistent(CODEC).networkSynchronized(STREAM_CODEC).build())`.

### Attachments (player progression, NPC memory)

NeoForge:

```java
public static final Supplier<AttachmentType<PlayerProgress>> PROGRESS = ATTACHMENTS.register("progress",
        () -> AttachmentType.builder(() -> PlayerProgress.EMPTY)
                .serialize(PlayerProgress.CODEC.fieldOf("progress"))    // MapCodec: persisted
                .sync((holder, to) -> holder == to, PlayerProgress.STREAM_CODEC) // only to the owner
                .copyOnDeath()
                .build());

PlayerProgress p = player.getData(PROGRESS);
player.setData(PROGRESS, p.withXp(p.xp() + 50)); // setData auto-syncs + marks dirty
```

Fabric:

```java
public static final AttachmentType<PlayerProgress> PROGRESS = AttachmentRegistry.create(id("progress"),
        b -> b.initializer(() -> PlayerProgress.EMPTY)
              .persistent(PlayerProgress.CODEC)
              .syncWith(PlayerProgress.STREAM_CODEC, AttachmentSyncPredicate.targetOnly())
              .copyOnDeath());
player.setAttached(PROGRESS, updated); PlayerProgress p = player.getAttachedOrCreate(PROGRESS);
```

Make attachment values **immutable records** and replace them with `setData`/`setAttached`.
Mutating a mutable object in place skips sync and dirty-marking.

### SavedData (global state)

```java
public class WorldEvents extends SavedData {
    public static final SavedDataType<WorldEvents> TYPE = new SavedDataType<>(id("world_events"),
            WorldEvents::new, RecordCodecBuilder.create(i -> i.group(
                    Codec.STRING.listOf().fieldOf("completed").forGetter(w -> List.copyOf(w.completed))
            ).apply(i, WorldEvents::new)));
    private final Set<String> completed = new HashSet<>();
    public WorldEvents() {}
    private WorldEvents(List<String> done) { completed.addAll(done); }
    public void complete(String id) { if (completed.add(id)) setDirty(); }
}
WorldEvents ev = server.getDataStorage().computeIfAbsent(WorldEvents.TYPE); // or serverLevel.getDataStorage()
```

## Data-driven content with reload listeners

```java
public record DialogueTree(...) { public static final Codec<DialogueTree> CODEC = ...; }

public class DialogueManager extends SimpleJsonResourceReloadListener<DialogueTree> {
    public static final Identifier ID = id("dialogue");
    private Map<Identifier, DialogueTree> trees = Map.of();
    public DialogueManager() { super(DialogueTree.CODEC, FileToIdConverter.json(MODID + "/dialogue")); }
    @Override protected void apply(Map<Identifier, DialogueTree> map, ResourceManager rm, ProfilerFiller p) {
        trees = Map.copyOf(map);
    }
    public Optional<DialogueTree> get(Identifier id) { return Optional.ofNullable(trees.get(id)); }
}
// Files: data/<ns>/<MODID>/dialogue/<name>.json  -> id <ns>:<name>
```

- NeoForge server data: `AddServerReloadListenersEvent` (game bus). To keep the data, use
  `event.addRetainedListener(ListenerKey, instance)` and fetch it from the server's resources.
  This avoids static singletons that leak across reloads or integrated-server worlds.
- NeoForge client assets: `AddClientReloadListenersEvent` (**mod bus**, despite what one doc
  comment says. The class implements `IModBusEvent`).
- Fabric: Resource Loader **v1** (`net.fabricmc.fabric.api.resource.v1.ResourceLoader`,
  `DataResourceLoader`) replaced the old `ResourceManagerHelper` API. Register the listener for
  `PackType.SERVER_DATA` / `CLIENT_RESOURCES` with an id. Check the exact method in the javadoc
  for your Fabric API version.
- Sync server-loaded definitions that the client needs (dialogue text, quest names) with a
  payload on join and after `/reload` (NeoForge `OnDatapackSyncEvent`, Fabric
  `ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS`). Or ship the client-only parts in `assets/`.

For content other mods should extend, or that needs `Holder` references and tags, make it a
**datapack registry** (NeoForge `DataPackRegistryEvent.NewRegistry`, Fabric
`DynamicRegistries.registerSynced`).

## Networking (custom payloads)

Payload shape (both loaders):

```java
public record ChooseDialogueOption(int npcEntityId, Identifier node, int option) implements CustomPacketPayload {
    public static final Type<ChooseDialogueOption> TYPE = new Type<>(id("choose_dialogue_option"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChooseDialogueOption> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChooseDialogueOption::npcEntityId,
            Identifier.STREAM_CODEC, ChooseDialogueOption::node,
            ByteBufCodecs.VAR_INT, ChooseDialogueOption::option,
            ChooseDialogueOption::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
```

NeoForge:

```java
@SubscribeEvent // mod bus
static void registerPayloads(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar r = event.registrar("1"); // bump on protocol changes
    r.playToServer(ChooseDialogueOption.TYPE, ChooseDialogueOption.STREAM_CODEC, ServerHandlers::onChoose);
    r.playToClient(OpenDialogue.TYPE, OpenDialogue.STREAM_CODEC);   // handler registered client-side:
}
@SubscribeEvent // mod bus, client only
static void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
    event.register(OpenDialogue.TYPE, ClientHandlers::onOpenDialogue);
}
// Handlers run on the main thread by default: (payload, IPayloadContext ctx) -> { ctx.player() ... }
PacketDistributor.sendToPlayer(serverPlayer, new OpenDialogue(...));
PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload);
ClientPacketDistributor.sendToServer(new ChooseDialogueOption(...));
```

Fabric:

```java
// common initializer
PayloadTypeRegistry.serverboundPlay().register(ChooseDialogueOption.TYPE, ChooseDialogueOption.STREAM_CODEC);
PayloadTypeRegistry.clientboundPlay().register(OpenDialogue.TYPE, OpenDialogue.STREAM_CODEC);
ServerPlayNetworking.registerGlobalReceiver(ChooseDialogueOption.TYPE, (payload, ctx) -> { ctx.player() ... });
ServerPlayNetworking.send(serverPlayer, new OpenDialogue(...));
// client initializer
ClientPlayNetworking.registerGlobalReceiver(OpenDialogue.TYPE, (payload, ctx) -> { ctx.client() ... });
ClientPlayNetworking.send(new ChooseDialogueOption(...));
```

Limits: clientbound up to 1 MiB, serverbound under 32 KiB. **Validate every serverbound
payload**: entity exists, `player.distanceToSqr(entity) < 64`, the option is legal for the
node the server thinks the player is on, rate-limit spam.

## Commands (debug and designer tools)

Add `/<modid> cutscene play <id> [targets]`, `/<modid> quest set ...` and similar commands early.
They speed up iteration a lot.

```java
// NeoForge: RegisterCommandsEvent (game bus). Fabric: CommandRegistrationCallback.EVENT
dispatcher.register(Commands.literal(MODID)
        // 26.x permission API (from Fabric's compiled 26.2 reference code):
        .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
        .then(Commands.literal("cutscene").then(Commands.argument("id", IdentifierArgument.id()) // net.minecraft.commands.arguments.IdentifierArgument
                .executes(ctx -> { /* start for ctx.getSource().getPlayerOrException() */ return 1; }))));
```

(26.x changed command permissions and responses. The 26.1 primer covers "Chat Permissions"
and the 26.3 primer covers "Command Responses".)

## Configs

NeoForge `ModConfigSpec` (`LOCAL`/`CLIENT`/`SERVER` types; `SERVER` syncs to clients). Fabric
has no built-in config: use a small codec-backed JSON file in `FabricLoader.getInstance().getConfigDir()`
or a library (owo-config, Cloth Config, YetAnotherConfigLib). Put comfort settings
(screen shake, cutscene skip, subtitle size) in client configs.

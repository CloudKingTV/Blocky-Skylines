# Progression: quests, skills, advancements, unlocks

Good progression gives the player a goal they can see, feedback while they work toward it,
a reward when they reach it, and a next goal after that.
Technically: **definitions are data, player state is an attachment, updates are server
events, and the client receives a synced snapshot for the HUD and journal.**

## Use vanilla advancements where they fit

Advancements are free UI (the advancement screen and toasts), persist per player, and are
data-driven: `data/<ns>/advancement/<path>.json`. Since 26.3 they are a **reloadable datapack
registry**.

```json5
{
  "parent": "mymod:story/root",
  "display": {
    "icon": {"id": "mymod:ancient_relic"},
    "title": {"translate": "advancements.mymod.relic.title"},
    "description": {"translate": "advancements.mymod.relic.description"},
    "frame": "goal", "show_toast": true, "announce_to_chat": true, "hidden": false
  },
  "criteria": { "got_relic": { "trigger": "minecraft:inventory_changed",
                 "conditions": { "items": [ { "items": "mymod:ancient_relic" } ] } } },
  "rewards": { "experience": 100, "loot": ["mymod:rewards/relic"], "function": "mymod:story/relic_found" }
}
```

Custom trigger for mod events (`SimpleCriterionTrigger`). **26.3 shape**:
`ContextAwarePredicate` is gone, and the player condition is `Optional<Holder<LootItemCondition>>`:

```java
public class TalkedToNpcTrigger extends SimpleCriterionTrigger<TalkedToNpcTrigger.Instance> {
    @Override public Codec<Instance> codec() { return Instance.CODEC; }
    public void trigger(ServerPlayer p, Identifier npc) { trigger(p, inst -> inst.npc().map(npc::equals).orElse(true)); }

    public record Instance(Optional<Holder<LootItemCondition>> player, Optional<Identifier> npc)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                // 26.3: LootItemCondition.CODEC is Codec<Holder<LootItemCondition>> (registry ref or inline);
                // DIRECT_CODEC is inline-only, LIST_CODEC is the HolderSet codec. (Verified in 26.3 sources.)
                LootItemCondition.CODEC.optionalFieldOf("player").forGetter(Instance::player),
                Identifier.CODEC.optionalFieldOf("npc").forGetter(Instance::npc)
        ).apply(i, Instance::new));
    }
}
// Register into BuiltInRegistries.TRIGGER_TYPES (Fabric: Registry.register; NeoForge: DeferredRegister.create(Registries.TRIGGER_TYPE, MODID))
// Fire: ModTriggers.TALKED_TO_NPC.get().trigger(serverPlayer, npcId);
```

(On 26.1/26.2 the field is `Optional<ContextAwarePredicate> player` with
`ContextAwarePredicate.CODEC`.) Datagen: 26.3 `AdvancementSubProvider` is an abstract class
constructed with a `BootstrapContext<Advancement>`. Reference parents by `Identifier`.

Award or revoke from code:
`player.getAdvancements().award(server.getAdvancements().get(id), "criterion")`
(26.3: advancements are registry holders, so look them up through the registry access if
`get` changed).

Use advancements for milestones and achievements. Use a quest system for multi-step story
objectives with tracked progress, NPC hand-ins and choices.

## Quest system

### Definition (data)

```json5
// data/mymod/mymod/quests/lost_tower.json
{
  "title": {"translate": "quest.mymod.lost_tower"},
  "description": {"translate": "quest.mymod.lost_tower.desc"},
  "icon": "mymod:ancient_relic",
  "requires": ["mymod:village_intro"],           // prerequisite quests
  "objectives": [
    { "id": "reach_tower", "type": "reach_structure", "structure": "mymod:ruined_tower",
      "hint": {"translate": "quest.mymod.lost_tower.reach"} },
    { "id": "slay_guardian", "type": "kill", "entity": "mymod:tower_guardian", "count": 1 },
    { "id": "get_relic", "type": "collect", "item": "mymod:ancient_relic", "count": 1, "consume": false },
    { "id": "return_relic", "type": "talk", "npc_tag": "elder" }
  ],
  "sequential": true,                              // objectives unlock one by one
  "rewards": { "loot_table": "mymod:quests/lost_tower", "xp": 250, "skill_points": 1,
               "unlock_recipes": ["mymod:rune_lantern"], "next": "mymod:the_deep_vault" }
}
```

Objective types as a codec dispatch registry: `kill`, `collect`, `craft`, `reach_structure`,
`reach_biome`, `reach_pos`, `talk`, `use_item`, `place_block`, `advancement`, `custom` (fired
from code by id). Each type declares which game event advances it.

### Player state (attachment)

```java
public record QuestProgress(Map<Identifier, QuestState> quests, Set<Identifier> flags, int skillPoints) {
    public static final QuestProgress EMPTY = new QuestProgress(Map.of(), Set.of(), 0);
    // CODEC (disk) + STREAM_CODEC (sync to owner only) ...
    public QuestProgress withQuest(Identifier id, QuestState s) {
        Map<Identifier, QuestState> m = new HashMap<>(quests); m.put(id, s);
        return new QuestProgress(Map.copyOf(m), flags, skillPoints);
    }
}
public record QuestState(Status status, Map<String, Integer> counters, long startedAt) {
    public enum Status { ACTIVE, COMPLETED, FAILED }
}
```

Register it as a persistent, `copyOnDeath`, owner-synced attachment (core-patterns.md). Every
update is `player.setData(PROGRESS, progress.withQuest(...))`, which makes sync automatic.

### Advancing objectives (server)

Hook the game events once and fan out to matching active objectives:

| Objective | NeoForge event | Fabric |
|---|---|---|
| kill | `LivingDeathEvent` (source entity is a `ServerPlayer`) | `ServerLivingEntityEvents.AFTER_DEATH` |
| collect | poll inventory every 20 ticks for active collect objectives, or `ItemEntityPickupEvent.Post` | tick poll |
| craft | `PlayerEvent.ItemCraftedEvent` | mixin on the result slot, or poll |
| reach_structure | every 20 ticks: `serverLevel.structureManager().getStructureWithPieceAt(pos, structureKey).isValid()` | same |
| talk | your `DialogueService` action `complete_objective` | same |
| advancement | `AdvancementEvent.AdvancementEarnEvent` | mixin on `PlayerAdvancements#award` |

On objective completion: play a sound, send a toast/title, and update the tracker. On quest
completion: grant rewards (roll a loot table with `LootParams` for `ServerPlayer`, give items
or drop overflow), award XP, unlock recipes (`player.awardRecipesByKey(...)`), start the `next`
quest, and fire your advancement trigger.

### Client presentation

- **Tracker HUD** (top-right, under the sidebar region): quest title + current objective
  hint + `3/5` counter. Pulse it and play a soft "tick" sound when a counter changes. Fade it
  when nothing changes for 30 s. Hide it with F1.
- **Journal screen** (keybind `J`): active/completed tabs, objectives with check states, a
  reward preview (item icons with tooltips), and lore text. Use nine-slice panels and a
  scroll list.
- **Waypoint**: an optional world-space beacon or compass hint toward the objective (submit a
  billboarded quad or particles at the target; clamp it to the screen edge as a HUD arrow when
  off-screen).
- **Toasts** for "Quest started", "Objective complete", "Quest complete".

## Skill trees and unlocks

- Define skills in a **datapack registry** (the Fabric docs' compiled example builds a
  "magic skills" dynamic registry with a screen). Each skill has: id, icon, position in the
  tree, cost, prerequisites, and effects.
- Effects should be **attribute modifiers** wherever possible:
  `player.getAttribute(Attributes.MOVEMENT_SPEED).addOrUpdateTransientModifier(new AttributeModifier(id("skill/swift"), 0.1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))`.
  Re-apply transient modifiers on login, respawn and dimension change (or use permanent
  modifiers with stable ids). Custom attributes go through `Registries.ATTRIBUTE`
  (`RangedAttribute`, `.setSyncable(true)`) and need to be added to the player's attributes
  (NeoForge `EntityAttributeModificationEvent`).
- Ability unlocks (dash, double jump, spell) are flags in the progress attachment, checked on
  the server when the ability payload arrives. The client checks them too, only to grey out
  UI.
- Tree screen: pannable canvas (`mouseDragged` updates an offset, `scissor` to the panel),
  nodes drawn as sprites, connections as lines (rotated thin `fill` quads via the pose
  stack), and states (locked / available / owned) with distinct art. An unlock gets a burst
  of particles and a sound.

## Gating content

- **Recipes**: give recipes `"show_notification"` and unlock them through quest/advancement
  rewards, or check progress in a custom recipe's `matches`.
- **Items**: check progress in `use`/`useOn` and send an action-bar hint
  ("The relic resists you... the Elder may know more").
- **Areas**: a barrier block that's passable only with a flag (a custom block with
  `getCollisionShape` keyed on an entity context, or teleport-back logic in a tick handler),
  or a structure that generates a sealed door opened by an interaction.
- **Dimensions/bosses**: summoning items consumed only if a prerequisite is met.

## Numbers that feel right

- XP-like curves: `cost(n) = round(base * n^1.5)`, or vanilla-like piecewise linear.
  Early levels come fast (1–3 minutes), later ones take 10–20 minutes.
- Reward something every 3–5 minutes of focused play early on (item, unlock, story beat).
- Each tier should change *how* you play (a new mechanic), not just add 5% damage.
- Test progression with a creative "fast-forward" debug command and a gametest that walks a
  quest from start to finish.

# NPCs and Dialogue

Pick the lightest tool that fits:

| Need | Tool |
|---|---|
| A few branching menus, works with vanilla clients, no client code | **Vanilla dialogs** (data-driven `dialog` registry since 1.21.6; `/dialog show`, `Player#openDialog`) |
| Map/modpack makers, no code at all | **Easy NPC** mod (Fabric/NeoForge, dialogs + actions + conditions) |
| Bespoke story NPCs: portraits, typewriter, voice, quests, cutscenes | **Your own system** (below) |

## The NPC entity

```java
public class ElderNpc extends PathfinderMob /* implements GeoEntity */ {
    @Nullable private UUID talkingTo;               // server-side session holder

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new TalkToPlayerGoal(this));    // freezes + faces the speaker
        goalSelector.addGoal(2, new MoveTowardsRestrictionGoal(this, 0.6)); // stays near home (restrictTo)
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 6f));
        goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.5));
        goalSelector.addGoal(5, new RandomLookAroundGoal(this));
    }

    // Right-click. Mob#mobInteract(Player, InteractionHand) is the override point (verified in 26.3;
    // 26.1 merged interactAt into Entity#interact(Player, Hand, Vec3), which Mob routes here).
    @Override protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level().isClientSide() && player instanceof ServerPlayer sp) {
            DialogueService.start(sp, this, id("elder/root"));
        }
        return InteractionResult.SUCCESS;   // both sides return success so the hand swings
    }

    @Override public boolean removeWhenFarAway(double dist) { return false; } // never despawn
    @Override public boolean isPushable() { return false; }
    @Override protected void doPush(Entity e) {}
    @Override public boolean isInvulnerableTo(ServerLevel level, DamageSource src) { return !src.isCreativePlayer(); }
    public void setTalkingTo(@Nullable UUID id) { this.talkingTo = id; }
    public @Nullable UUID talkingTo() { return talkingTo; }
}

class TalkToPlayerGoal extends Goal {
    private final ElderNpc npc;
    TalkToPlayerGoal(ElderNpc npc) { this.npc = npc; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    @Override public boolean canUse() { return npc.talkingTo() != null; }
    @Override public void start() { npc.getNavigation().stop(); }
    @Override public void tick() {
        Player p = npc.level().getPlayerByUUID(npc.talkingTo());
        if (p == null || p.distanceToSqr(npc) > 100) { npc.setTalkingTo(null); return; }
        npc.getLookControl().setLookAt(p, 30f, 30f);
    }
}
```

NPC polish: a name above the head (`setCustomName` + `setCustomNameVisible(true)`, or a
custom name-tag render), an idle "talk" animation while a session is open (see
entities-and-animation.md), a small particle or icon above NPCs with new dialogue (a `!`/`?`
billboard: submit a quad in the renderer, or use a GUI-atlas sprite in a `RenderLayer`), a
home position via `restrictTo(pos, radius)`, and day/night schedules if the world feels static.

## Dialogue content (data-driven)

`data/<ns>/<modid>/dialogue/elder/root.json` (loaded by a server reload listener; see
core-patterns.md):

```json5
{
  "speaker": {"translate": "entity.mymod.elder"},
  "portrait": "mymod:textures/gui/portraits/elder.png",   // optional; else render the entity
  "voice": {"sound": "mymod:voice_blip", "pitch": 0.85},
  "nodes": {
    "start": {
      "text": {"translate": "mymod.dialogue.elder.greeting"},
      "choices": [
        { "text": {"translate": "mymod.dialogue.elder.ask_tower"}, "next": "tower" },
        { "text": {"translate": "mymod.dialogue.elder.got_relic"}, "next": "thanks",
          "conditions": [{ "type": "has_item", "item": "mymod:ancient_relic" }] },
        { "text": {"translate": "gui.mymod.goodbye"}, "next": "end" }
      ]
    },
    "tower": {
      "text": {"translate": "mymod.dialogue.elder.tower"},
      "actions": [{ "type": "start_quest", "quest": "mymod:lost_tower" },
                  { "type": "play_cutscene", "cutscene": "mymod:tower_reveal" }],
      "next": "end"
    },
    "thanks": {
      "text": {"translate": "mymod.dialogue.elder.thanks"},
      "actions": [{ "type": "take_item", "item": "mymod:ancient_relic" },
                  { "type": "complete_objective", "quest": "mymod:lost_tower", "objective": "return_relic" },
                  { "type": "give_item", "item": "minecraft:emerald", "count": 12 }],
      "next": "end"
    }
  }
}
```

Model conditions and actions as **registries of codecs** (`Codec.STRING.dispatch("type", ...)`
or a custom registry of `MapCodec`s), so new types plug in cleanly and the quest system can
reuse them. Useful conditions: `has_item`, `quest_state`, `objective_done`, `advancement`,
`flag`, `time_of_day`, `random_chance`, `not`/`any`/`all`. Useful actions: `give_item`,
`take_item`, `start_quest`, `complete_objective`, `set_flag`, `play_cutscene`, `open_trades`,
`trigger_anim`, `run_function` (data-pack function), `goto`.

## Server-authoritative flow

```java
public final class DialogueService {
    private record Session(int npcId, Identifier tree, String node) {}
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void start(ServerPlayer p, ElderNpc npc, Identifier tree) {
        npc.setTalkingTo(p.getUUID());
        show(p, npc, tree, "start");
    }

    static void show(ServerPlayer p, Mob npc, Identifier treeId, String nodeId) {
        DialogueTree tree = DialogueManager.get(p.level().getServer(), treeId).orElseThrow();
        if (nodeId.equals("end")) { close(p, npc); return; }
        DialogueNode node = tree.nodes().get(nodeId);
        node.actions().forEach(a -> a.run(p, npc));                                 // server runs actions
        List<Component> visible = new ArrayList<>(); List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < node.choices().size(); i++)
            if (node.choices().get(i).test(p, npc)) { visible.add(node.choices().get(i).text()); indices.add(i); }
        SESSIONS.put(p.getUUID(), new Session(npc.getId(), treeId, nodeId));
        PacketDistributor.sendToPlayer(p, new OpenDialogue(npc.getId(), tree.speaker(), node.text(),
                tree.voice(), visible, indices, node.next().isPresent()));
    }

    public static void onChoose(ChooseDialogueOption msg, ServerPlayer p) {          // payload handler
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || s.npcId() != msg.npcEntityId()) return;                      // stale or forged
        if (!(p.level().getEntity(s.npcId()) instanceof Mob npc) || npc.distanceToSqr(p) > 64) { close(p, null); return; }
        DialogueNode node = DialogueManager.get(p.level().getServer(), s.tree()).orElseThrow().nodes().get(s.node());
        if (msg.option() < 0) { show(p, npc, s.tree(), node.next().orElse("end")); return; }   // "continue"
        if (msg.option() >= node.choices().size()) return;
        DialogueChoice c = node.choices().get(msg.option());
        if (!c.test(p, npc)) return;                                                   // re-check conditions!
        show(p, npc, s.tree(), c.next());
    }

    static void close(ServerPlayer p, @Nullable Mob npc) {
        SESSIONS.remove(p.getUUID());
        if (npc instanceof ElderNpc e) e.setTalkingTo(null);
        PacketDistributor.sendToPlayer(p, CloseDialogue.INSTANCE);
    }
}
```

Also end sessions on logout, dimension change and NPC death, and when the client closes the
screen (send a `CloseDialogue` serverbound).

## Client dialogue screen

Layout: a translucent panel across the bottom third; speaker name tab on its top-left edge;
a portrait on the left (a texture, or a live render of the NPC via `GuiGraphicsExtractor`'s
entity picture-in-picture); typewriter body text; choices stacked on the right or below,
numbered `1.`–`9.`.

Behavior:
- `isPauseScreen()` returns false (the world keeps moving: NPC animations, ambience).
- Typewriter at about 40 cps with punctuation pauses. **Voice blips** every 2 visible letters,
  skipping spaces, at the NPC's base pitch ± 0.05 random. Use a different base pitch per NPC
  for personality.
- Click/Enter/Space: if still typing, finish the line instantly. Otherwise continue (send
  option `-1`) or, when choices are shown, do nothing until one is picked. Number keys pick
  choices. Esc closes and tells the server.
- Choices fade in staggered (40 ms apart) only after the text finishes.
- Optional dialogue camera: ease the FOV down 5–8° and nudge the view toward the NPC's face
  (NeoForge `ViewportEvent.ComputeFov`/`ComputeCameraAngles`) while the screen is open.
- Keep text in components so styles (colored keywords, item names with hover) and translations work.

## Vanilla dialogs (no client mod needed)

Since 1.21.6 data packs can define dialogs under `data/<ns>/dialog/*.json` and show them with
`/dialog show @p <ns>:<id>` or `player.openDialog(holder)`. Types include `notice`,
`confirmation`, `multi_action`, `dialog_list` and `server_links`. Bodies are `plain_message`
and `item`. Inputs include text, boolean, single-option and number-range. Buttons run click
actions (run a command, show another dialog, open a URL, or **send a custom payload to the
server**: `minecraft:custom` / `dynamic/custom` with your id, which your mod handles).

```json5
// data/mymod/dialog/elder_greeting.json  (check field names against the current Minecraft Wiki "Dialog" page)
{
  "type": "minecraft:multi_action",
  "title": {"translate": "entity.mymod.elder"},
  "body": [{"type": "minecraft:plain_message", "contents": {"translate": "mymod.dialogue.elder.greeting"}}],
  "actions": [
    {"label": {"translate": "mymod.dialogue.elder.ask_tower"},
     "action": {"type": "minecraft:run_command", "command": "/function mymod:dialogue/elder_tower"}},
    {"label": {"translate": "gui.mymod.goodbye"}}
  ],
  "can_close_with_escape": true,
  "pause": false
}
```

Mods can add custom body types, input controls and actions (registries
`DIALOG_BODY_TYPE`, `INPUT_CONTROL_TYPE`, `DIALOG_ACTION_TYPE`, with client handlers in
`DialogBodyHandlers`/`InputControlHandlers`, whose `register` needs an access transformer or
class tweaker). This is a good path for server-side mods and for quick prototypes before
building a bespoke screen.

## Trading NPCs

- Simplest: implement `Merchant` on the NPC and call `openTradingScreen(player, name, level)`.
  Vanilla's `MerchantScreen` handles the UI, so offers come from your data.
- 26.1 made villager trades data-pack driven (trade sets with item listings and conditions).
  Read the 26.1 primer section "Datapack Villager Trades" and reuse the format for NPC shops,
  so designers price items in JSON.
- Custom shop UI: a `MenuType` + `AbstractContainerScreen` with server-validated purchase
  buttons (see ui-screens-hud.md). Check the player's currency on the server at purchase time.

## Writing good NPC dialogue (content craft)

- 1–3 short sentences per node. Players skim. Put the key noun (place, item, enemy) in
  colored text.
- Every choice should feel different (tone or outcome). Fill in "Goodbye" and "Tell me again"
  automatically.
- NPCs react to state: the quest started, the relic was found, it's night, the player is
  hurt, the player has completed the quest.
- Repetition is the enemy: random greetings pool (`"text": [..]` picked with the level random),
  and shorter text on repeat visits.
- Signpost the next step: the last line before closing should say where to go or what to get,
  matching the quest tracker text.

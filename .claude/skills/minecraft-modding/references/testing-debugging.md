# Testing, Debugging, Performance, Release

## Verify before you claim it works

1. `./gradlew build` compiles and runs server gametests (Fabric runs them during `build`).
2. `./gradlew runClient`: exercise the feature by hand: open the screen, trigger the
   cutscene, talk to the NPC, finish the quest, and also at GUI scale 1 and 4.
3. `./gradlew runServer`: a dedicated server must start and a client must join. This
   catches `NoClassDefFoundError: net/minecraft/client/...` (client code reachable from common
   code).
4. Gametests for logic: quest state transitions, objective counting, dialogue condition
   evaluation, payload validation.
5. Second client (`runClient` twice with different usernames, or a LAN world) for anything
   synced: attachments, cutscene groups, NPC sessions.

If the sandbox can't reach the mavens or Mojang's servers, say so plainly and list exactly
which steps the user needs to run locally.

## GameTests

NeoForge 26.x: tests are **data-driven instances** at `data/<ns>/test_instance/<name>.json`,
pointing at a test function, environment and structure. Alternatively, register in code on
the mod bus with `RegisterGameTestsEvent` (`registerEnvironment`, `registerTest`). Run them
with `./gradlew runGameTestServer` or in game with `/test runall`, `/test runclosest`,
`/test runfailed`. Enable namespaces with
`-Dneoforge.enabledGameTestNamespaces=<modid>` (the MDK sets this).

Fabric: `fabricApi { configureTests { createSourceSet = true; enableGameTests = true; enableClientGameTests = true; eula = true } }`.
Tests live in `src/gametest/java`, with entrypoints `fabric-gametest` (server) and
`fabric-client-gametest` (client: drives real input, screenshots). Methods are annotated
`@GameTest` and take a `GameTestHelper`; call `helper.succeed()` or the assert helpers.
`runClientGameTest` runs client tests. `runProductionClientGameTest` (with XVFB) is for CI.

```java
@GameTest
public void elderGivesQuest(GameTestHelper h) {
    ServerPlayer p = h.makeMockServerPlayerInLevel();       // verify helper name for your version
    ElderNpc elder = h.spawn(ModRegistries.ELDER.get(), 2, 1, 2);
    DialogueService.start(p, elder, id("elder/root"));
    DialogueService.onChoose(new ChooseDialogueOption(elder.getId(), id("elder/root"), 0), p);
    h.assertTrue(p.getData(PROGRESS).quests().containsKey(id("lost_tower")), "quest should start");
    h.succeed();
}
```

Pure logic (easing curves, camera path sampling, codec round-trips) belongs in **plain JUnit**
(Fabric Loader JUnit, or a normal test source set), which runs in milliseconds.

## Debugging toolbox

- **Logs**: `logs/latest.log` and `logs/debug.log` in the run dir. NeoForge: add
  `systemProperty 'forge.logging.markers', 'REGISTRIES'` for registry problems.
- **Crash reports**: `crash-reports/`. Read the "Caused by" chain bottom-up. The first frame
  in your package is usually where the bug is.
- **Hotswap**: debug-run from the IDE. Method-body changes hot-reload (JetBrains Runtime with
  `-XX:+AllowEnhancedClassRedefinition` allows more). Resources reload with F3+T (assets) and
  `/reload` (data).
- **F3 screens**: F3 (coords, facing, targeted block/entity), F3+B (hitboxes), F3+G (chunk
  borders), F3+H (advanced tooltips with ids and components), F3+L (profiler capture), and
  the 26.x debug options screen for renderer overlays.
- **Mixin**: `-Dmixin.debug.export=true` dumps transformed classes to `.mixin.out/`.
  `-Dmixin.debug.verbose=true` explains failed injections. With an injection point that can't
  be found, re-check the target method name/descriptor in the decompiled sources for this
  exact version.
- **Rendering**: RenderDoc works with both GL and Vulkan backends. Debug labels are enabled
  with GPU debug options.

## Common failures and fixes

| Symptom | Likely cause |
|---|---|
| `NoClassDefFoundError`/`ClassNotFoundException` for `net.minecraft.client.*` on server | Client class referenced from common code. Move it behind `Dist.CLIENT` / the Fabric client source set |
| Purple-black checkerboard texture | Wrong path/namespace, missing `.png`, or texture not in an atlas (GUI sprites must live under `textures/gui/sprites/`) |
| Item renders as nothing or missing | No `assets/<ns>/items/<item>.json` client item (needed since 1.21.4) |
| Block suddenly translucent or wrongly sorted (26.1+) | Semi-transparent pixels in the texture. Layers are inferred from alpha; run `texture_tools.py check` |
| Text field ignores typing (26.3) | Missing `onTextInputFocusChange` on focus |
| Keybind triggers wrong key (26.3) | Raw GLFW int used; switch to `InputConstants.KEY_*` (SDL scancodes) |
| `Registry is already frozen` / `Not bound` | Registering at the wrong time, or calling `.get()` on a DeferredHolder before registration |
| Data doesn't reach the client | No `networkSynchronized` / `.sync(...)` / `syncWith`, or mutating an attachment value in place instead of `setData` |
| Entity invisible | Renderer not registered, layer definition not registered, or texture size ≠ `LayerDefinition` size |
| Animation doesn't play on other clients | Started client-side only. Drive it from synced data or `broadcastEntityEvent`/`triggerAnim` on the server |
| Desync/rubber-banding NPC | Moving entities client-side. Move them on the server only |
| Mixin `InvalidInjectionException` after updating | The target was renamed in the new version. Check the primer |

## Performance

- Server: avoid per-tick scans of all entities/players. Throttle (`tickCount % 20`), index by
  chunk, and cache structure lookups. Don't load chunks from tick handlers
  (`level.isLoaded(pos)` first).
- Network: send deltas or small records, not full state every tick. Batch updates.
- Client: see rendering-and-vfx.md. Most frame-time problems come from allocation in hot
  paths, or rendering things out of view.
- Use Spark (profiler mod) for in-the-wild profiling.

## Release checklist

- [ ] Version bumped; changelog written; `neoforge.mods.toml`/`fabric.mod.json` metadata (description, authors, license, icon, links)
- [ ] Dependency ranges honest (Minecraft range, loader, GeckoLib etc. marked required)
- [ ] Builds clean from a fresh clone; no dev-only deps leaked into the jar (`localRuntime`)
- [ ] Dedicated server start test; client join test; fresh-world test; existing-world upgrade test
- [ ] All strings translated (no raw keys in game); subtitles for every sound
- [ ] Configs have sane defaults; comfort options (shake, cutscene skip) present
- [ ] Datagen re-run and committed (`src/generated/resources`)
- [ ] Gametests green
- [ ] Screenshots/GIFs of the polished features for the mod page

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

## Running the real client headless (and taking screenshots)

A sandbox has no display, but the 26.3 client runs on Mesa's software OpenGL through SDL's
**offscreen** driver (verified 2026-10-18: "Using graphics backend OpenGL ... Mesa", client mixins
applied, world loaded). Xvfb does **not** work: Minecraft requests an sRGB-capable framebuffer
(`SDL_GL_FRAMEBUFFER_SRGB_CAPABLE`) and Xvfb's GLX visuals have none ("Couldn't find matching GLX
visual"). The installed Vulkan also lacks `VK_KHR_surface`, so the game falls back to OpenGL.

```bash
sudo apt-get install -y libegl1 libgl1-mesa-dri mesa-utils     # Mesa EGL + llvmpipe
# NeoForge: turn off the early loading window (it needs a real display)
sed -i 's/^earlyWindowControl = true/earlyWindowControl = false/' run/config/fml.toml   # exists after the first launch
mkdir -p /tmp/xdg && chmod 700 /tmp/xdg
SDL_VIDEODRIVER=offscreen EGL_PLATFORM=surfaceless XDG_RUNTIME_DIR=/tmp/xdg \
  JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./gradlew runClient
```

There's no audio device ("Failed to open OpenAL device" is expected), and the
`discovery.minecraftservices.com` lookup fails harmlessly.

To get into a world without clicking:
- Quick play only opens **existing** worlds: run `runServer` once (accept `run/eula.txt`), copy
  `run/world` to `run/saves/testworld`, and add `programArguments.addAll '--quickPlaySingleplayer', 'testworld'`
  to the client run.
- Script the session with a dev-only `ClientTickEvent.Post` handler gated on a system property
  (`-D<modid>.autotest=true`). Count ticks after `mc.player != null`. Run setup on the integrated
  server (`mc.getSingleplayerServer().execute(...)`: commands via
  `server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon")`,
  spawning via `TYPE.create(level, EntitySpawnReason.COMMAND)` + `snapTo` + `addFreshEntity`). Open
  screens with `mc.gui.setScreen(...)`.
- Screenshot: `Screenshot.grab(mc.gameDirectory, "name.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> {})`
  writes to `run/screenshots/`. **Look at them with the Read tool.** That's how you check textures,
  UI layout and cutscene framing. Finish with `mc.stop()`.
- **First launch stalls on the accessibility onboarding screen**, and quick play waits behind it.
  After the first launch, set `onboardAccessibility:false` and `tutorialStep:none` in `run/options.txt`.
  Have the driver log `mc.gui.screen()` while `mc.player == null` so stalls are diagnosable.
- Calling `mc.stop()` from inside a tick handler ends the run but then crashes during shutdown
  ("Cannot close a surface while it is acquired"), and chunk saves get rejected. It's harmless
  for a throwaway test world. Don't mistake it for a mod bug.
- Never commit the autotest hook or the quick-play args. Keep them in a scratch copy of the project.

### Playtesting with a simulated keyboard and mouse (verified on 26.3)

To judge how controls *feel*, drive Minecraft's own input handlers rather than calling your
screen methods. This exercises key mappings, screen dispatch, focus and text input exactly like a
player would:

```java
long h = mc.getWindow().handle();
mc.keyboardHandler.keyPress(h, InputConstants.PRESS, new KeyEvent(InputConstants.KEY_W, 'w', 0)); // (scancode, SDL keycode, mods)
mc.keyboardHandler.keyPress(h, InputConstants.RELEASE, new KeyEvent(InputConstants.KEY_W, 'w', 0));
"Oakbrook".codePoints().forEach(cp -> mc.keyboardHandler.charTyped(h, new CharacterEvent(cp)));
mc.mouseHandler.onMove(h, x, y, x - lastX, y - lastY);          // window pixels (GUI px × gui scale)
mc.mouseHandler.onButton(h, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0), InputConstants.PRESS);
mc.mouseHandler.onScroll(h, 0, -4);                               // 4 notches toward the player
```

- **Fill in the SDL keycode.** `EditBox` (and anything using `KeyEvent#shortcutKey`) reads the
  *keycode*, not the scancode: with keycode 0, Backspace/Ctrl+A/Enter silently do nothing. Letters
  are their lowercase ASCII, Backspace 8, Enter 13, Esc 27; navigation keys are
  `0x40000000 | scancode` (Home = 1073741898, End = 1073741901, LShift = 1073742049).
- Log the numbers that matter each tick (camera position, yaw, zoom) as CSV lines and read them
  back: "reaches 90% speed in 4 ticks, stops within 3" is a far better feel check than a
  screenshot.
- Software rendering runs the game far slower than real time, so anything timed in
  **milliseconds** (toasts, flashes, fades) can expire between two ticks. Screenshot the tick
  right after the input, or drive UI timers from ticks in tests.
- `Runtime.getRuntime().halt(0)` at the end skips the shutdown crash entirely.
- Scripted clicks right after a move catch a real bug class: hover/raycast state that only updates
  while a frame draws. In `mouseClicked`, re-aim from the event's own x/y before acting.
- Make scripted worlds deterministic: the spawn point moves between runs, so level a test plot
  (`setBlock` with flags `2 | 16`) and place the setup relative to it, or strokes land on lakes and
  buildings.

Software rendering is slow (single-digit to low-tens FPS), so leave 2–3 s of ticks between
opening something and screenshotting it.

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

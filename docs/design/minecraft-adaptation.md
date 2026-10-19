# Townstead: Bringing Cities: Skylines to Minecraft

*Working title was "Blocky Skylines". The mod is **Townstead** (mod id `townstead`),
tagline "From hamlet to metropolis." The repository keeps its old name.*

This document turns the research in [`../research/cities-skylines.md`](../research/cities-skylines.md)
into a Minecraft design. It covers what to keep, what has to change, how each system maps onto
Minecraft's engine, and a phased plan. Technical notes assume NeoForge 26.3 and the
`minecraft-modding` skill in this repo. Settled decisions are in §9, and the survival
supply design is in §10.

---

## 1. The pitch

> **Plan from above, live on the ground.**
> Lay out roads, paint zones and place services from a bird's-eye **Planner view**, and watch
> your city build itself block by block. Then drop down and **walk the streets you designed**,
> among the citizens who live there.

In Cities: Skylines you never inhabit your city. First-person views (mods, CS2 photo mode)
are spectator cameras; you can't act as a person in it. Minecraft is built around inhabiting a world. That's the opening:

1. **Two modes**: the *Mayor* (Planner view, RTS camera, tool palette, info views) and the
   *Citizen* (normal first-person Minecraft inside the city).
2. **Multiplayer cities** (CS never had multiplayer): co-mayors, neighboring cities trading on a
   server, players living in each other's towns.
3. **Minecraft-flavored progression**: monuments that need Minecraft feats (an End Gateway
   Plaza after the dragon falls), districts that harvest real resources, and disasters drawn
   from Minecraft (lightning fires, floods, Pillager raids).

### Competitive landscape (why this isn't already done)

| Mod | What it is | Gap Blocky Skylines fills |
|---|---|---|
| **MineColonies** (NeoForge 1.21.1, active) | Colony sim: you build huts from schematics, citizens with jobs gather and craft | It's a *colony/automation* game: you place every building and feed resources. There's no zoning, demand, land value, traffic or top-down planning |
| **Modern City with Citizens** (Fabric 26.1.2) | Generates cities with citizens commuting between homes and shops | World generation, not player city *management* |
| Road/decor mods (CityUtils, Fureniku's Roads, city modpacks) | Road blocks, signs, traffic-light props | Decoration only, with no simulation |

Nobody has built the **zone → demand → growth → services → level-up** loop with a planner
camera in Minecraft. That loop is the identity of Cities: Skylines, and it's the core of this mod.

---

## 2. Hard constraints of Minecraft (design around these)

| Constraint | Consequence | Design answer |
|---|---|---|
| **Scale**: 1 block = 1 m. CS's zone cell is 8 m | A CS zone cell is exactly **8×8 blocks**, so a chunk is 2×2 cells. Real-scale CS cities (km wide) are impossible | Keep the 8-block cell (true 1:1 with CS), but build **towns**, not megacities: tiles of **64×64 blocks** (4×4 chunks = 8×8 cells), starting with 3×3 tiles (192 blocks) and growing to around 9×9 (576 blocks) |
| **Render distance** (2–32 chunks; players usually run 8–16) | A top-down view can't show a huge city. Chunks are sent around the **player's** position, not a detached camera | In Planner mode the **player body becomes the camera** (invisible, flying, no-clip, high altitude), so the server streams chunks around it. Recommend a LOD mod (Distant Horizons/Voxy) for big cities and make the mod compatible |
| **Chunks unload** when no player is near | A city can't depend on loaded chunks to "live" | The **simulation is abstract** (SavedData), independent of loaded chunks. Block changes queue and apply when the chunk loads (§6.3) |
| **Entity cost**: hundreds of entities per area hurts TPS. Mobs need loaded chunks | Can't embody every citizen or car (CS1 itself capped embodied citizens at about 65k and vehicles at about 16k) | Population is **data rows**. Embody a **sample**: 20–60 ambient citizen NPCs near players, and **client-side visual traffic** (no server entities) following the road graph (§6.5) |
| **Terrain is rugged and editable** | CS zones flat lots. Minecraft land is hills, caves, trees, water | Lots **auto-grade** (foundations and fill, cut slopes). Roads grade terrain along their path. Provide a terraform brush in the planner. Zoning is invalid on steep or watery cells |
| **Building art volume**: CS ships hundreds of models | Hand-building hundreds of structures is a huge content cost | **Hybrid buildings**: procedural generation from style palettes and rules for most growables, plus hand-made NBT templates for services, unique buildings and monuments. All data-driven so packs can add more (§6.4) |
| **Multiplayer server** | Many players, permissions, griefing | Cities have owners/co-mayors/residents. Planner tools respect claims. Abstract simulation runs once on the server |
| **Survival vs creative** | Where do buildings' blocks come from? | **Decided: survival materials.** Construction consumes items from the Town Storehouse; industry later supplies it (§10). A `freeBuild` world option skips materials |

---

## 3. System-by-system mapping

Difficulty: ★ easy · ★★ moderate · ★★★ hard. Phase refers to the roadmap in §8.

| CS system | Blocky Skylines version | Diff. | Phase |
|---|---|---|---|
| **RTS camera & tool palette** | **Planner view**: a toggle key. The player becomes a flying, invisible camera at altitude; a mouse cursor raycasts into the world; a bottom toolbar screen with categories; WASD pan, scroll zoom, Q/E rotate, with eased motion. Built on the verified camera hook (`Camera#update` mixin), the `ComputeFov` event and custom GUI layers | ★★★ | 0 |
| **City founding & tiles** | Place a **Town Hall** to found a city and claim the center tile. Buy adjacent 64×64 tiles with milestone permits plus money. Tile borders are drawn in planner view | ★★ | 0 |
| **Roads** | Road **graph** (nodes, segments, lanes, type) in SavedData. Straight, curve and freeform tools with snapping and a guideline preview (ghost blocks). Each road type is a **cross-section profile** (asphalt, lines, sidewalks, trees, lamps) rasterized to blocks along the curve. Junctions are auto-generated. Grading through terrain, bridges, tunnels. Upgrade tool | ★★★ | 1 |
| **Zoning grid** | Cells auto-generated 4 deep beside zonable roads (8×8-block cells). Paint/fill/erase brushes. Zones R (low/high), C (low/high), I, O. Shown as colored translucent overlays only in planner view | ★★ | 2 |
| **Demand** | RCIO bars computed from jobs vs workers, consumer demand, goods and education (§5). Simple and legible, with visible reasons | ★★ | 2 |
| **Growables** | When demand > 0 on a valid lot, pick a building (procedural or template) for zone/density/level/footprint/district style. **Animated construction**: scaffolding, then layers of blocks appear over about 30–90 s with dust particles and sounds. Level-ups rebuild in place. Abandonment shows boarded-up windows and cobwebs | ★★★ | 2 |
| **Utilities** | **Utilities follow roads** (CS2 style): power and water networks are the road graph, plus explicit power lines/pipes for gaps. Producers: coal plant, wind turbine (Minecraft height = more wind), solar, water mill/hydro, nuclear (late). Water pump on a river, water tower, sewage outlet that visibly pollutes water downstream | ★★ | 3 |
| **Services** | Ploppable structures (hand-made templates) with **coverage radius** previewed on placement: clinic, hospital, fire station, police, schools, landfill/incinerator, cemetery/crematorium, parks. Capacity limits. Vehicles are visual-only (§6.5) or abstract | ★★ | 3 |
| **Citizens** | Households as data: age stage, education, job, happiness, health. Lifecycle and education pipelines with school capacity. Death and illness. Move-in/out. **Ambient NPCs** (custom citizen entity with GeckoLib or vanilla keyframe animations) sampled from real households: click one to see their home, job and mood | ★★★ | 4 |
| **Happiness, land value, pollution, noise, crime** | Per-cell scalar fields on a coarse grid (one value per 8×8 cell), updated incrementally. Sources stamp radial falloffs. Every field has an **info view** | ★★ | 4 |
| **Leveling** | R 1–5, C/I/O 1–3 with CS-like requirements (education, land value, services). **Progress bar with missing factors** in the building panel | ★★ | 4 |
| **Economy** | Treasury, taxes per zone (−10%..30%), upkeep, budget sliders (50–150%), loans. Income/expense panel and graph. **Keep it legible** (CS2 lesson) | ★★ | 4 |
| **Progression** | Population **milestones** unlock tool categories, tiles and policies. Optionally an **XP + development tree** (CS2-style) for service variants. **Unique buildings** with conditions; **monuments** gated by Minecraft feats | ★★ | 5 |
| **Info views** | Heat-map overlays drawn as colored quads on top of terrain in planner view (custom geometry submission), plus a legend. Status **icons above buildings** (billboards: no power, no water, garbage, sick, abandoned, fire) | ★★ | 3 |
| **Chirper** | **"Chirps"**: a slide-in HUD feed of citizen posts with skins/portraits, prioritized by severity, clickable to fly the camera to the problem | ★ | 5 |
| **Districts & policies** | Paint districts with names shown in the world. Policies as toggles (city-wide and per district) with costs and effects. Specializations (forest, farming, ore, oil) map onto Minecraft biomes and ores | ★★ | 7 |
| **Public transport** | Bus lines (stops on roads, a depot) with client-rendered buses, ridership stats and a traffic reduction effect. Later: minecart metro, which is *very* Minecraft | ★★★ | 6 |
| **Traffic** | Abstract flow model: trips are assigned to the road graph with a capacity-based congestion formula (BPR-style), recalculated periodically. Congestion is shown in a traffic info view and in the density of visual cars. No per-car server agents | ★★★ | 6 |
| **Disasters** | Minecraft-native: lightning fires (fire service), river floods (dams), sinkholes into caves, **Pillager raids** (police/guard towers), creeper storms (comedic, optional) | ★★ | 7 |
| **Production chains** | Industry areas whose extractors harvest *real* resources (logs, crops, ore) into a city warehouse, which can supply survival construction or be exported for income | ★★★ | 7+ |
| **Day/night, weather** | Free from Minecraft. Night lighting via lamps placed in road profiles. Rain raises fire safety, snow needs plowing | ★ | — |
| **Modding** | All buildings, road profiles, zones, policies, milestones and unique-building conditions are **data-driven** (datapack/resource-pack JSON + NBT), so creators can ship packs | ★★ | Throughout |

---

## 4. Signature UX: the Planner view

This is the make-or-break feature. It needs to feel like Cities: Skylines, not like flying in
creative mode.

- **Enter/exit:** press a key (configurable; for example `M`) inside your city. A
  smooth 1 s camera ease up to about 60 blocks above the cursor target, with a fade and the HUD
  swapping to the planner HUD. Exit returns you to where you stood.
- **Camera:** WASD pans parallel to the ground (speed scales with height), scroll zooms
  (altitude 12–160), middle-drag or Q/E rotates, and the pitch tilts with zoom (top-down when high,
  oblique when low), like CS. Implemented by driving the player's position and rotation (server
  authority for chunk streaming), with the `Camera#update` mixin for smoothing and roll.
- **Cursor:** the mouse is free (a screen without pause). A raycast from the camera through the
  cursor finds the world position, snapped to cells and road nodes.
- **Toolbar:** a bottom bar with category tabs (Roads, Zoning, Districts, Utilities, Services,
  Transport, Parks, Unique, Bulldoze), thumbnails, costs and tooltips. Pick a tool, and the
  cursor shows a **ghost preview** (translucent blocks or outline) plus the **coverage radius**
  ring.
- **Top bar:** money, income/hr, population, happiness, demand bars, date/time, speed controls,
  and a milestone progress bar.
- **Info views:** a button row or hotkeys that swap the world coloring to an overlay.
- **Building panel:** click a building to see its name, level and progress (missing
  factors), residents/workers, problems and services coverage.
- **Undo/redo** for planner actions (CS1 lacked it, and players added it via mods).
- **Feel:** eased camera, soft click sounds, placement "thunk" plus dust particles, and a growth
  animation, consistent with the skill's polish rules.

---

## 5. A simulation model sized for Minecraft

Principles: legible over deep, incremental over global, and **tick-budgeted** (simulation work
is spread across ticks and never exceeds about 2 ms/tick for a medium city).

**Time:** 1 in-game day (20 min) = 1 "city week". Monthly budget settlement every 4 days.

**Entities in data (SavedData `CityData`):**
```
City { id, name, owner, coMayors[], treasury, taxRates{R,C,I,O}, budgets{service→%},
       loans[], milestone, unlocks{}, tiles{}, policies{}, districts{}, stats history }
RoadGraph { nodes[], segments[ type, curve, lanes, length, capacity, flow ] }
Lot { id, cells[], zone, density, buildingId?, level, state(empty|constructing|active|abandoned|burned),
      households[], jobs[], needs flags, happinessCache }
Household { members[ age, education, jobId?, health ], wealth, homeLot }
ServiceBuilding { type, pos, capacity, coverageRadius, budget, load }
Fields (per 8×8 cell): landValue, groundPollution, noise, crime, coverage[service]
```

**Update cadence (staggered round-robin):**
- Every second: construction progress, cheap demand update.
- Every ~5 s: a slice of lots re-evaluates needs (power/water/service coverage), happiness,
  level-up and abandonment.
- Every ~30 s: fields recompute incrementally (dirty cells only), job matching, education
  graduation, aging, births and deaths, traffic assignment.
- Monthly: taxes, upkeep and loan payments, milestone checks.

**Demand (legible formulas, tunable in JSON):**
- `R = clamp(openJobs − unemployed − vacantHomes×k)`
- `C = clamp(population × goodsPerCapita − commercialCapacity)`
- `I = clamp(commercialGoodsNeed + exportDemand − industrialCapacity)`
- `O = clamp(highlyEducatedUnemployed − officeJobsOpen)`

Each bar's tooltip **lists its top drivers**. That's how we avoid CS2's opaque-economy problem.

**Leveling:** score = weighted sum of {education, landValue, serviceCoverage, happiness}. When
it holds above the threshold for N days, the building levels up. The building panel shows
each factor's contribution.

**Traffic (abstract):** origin-destination pairs come from homes → jobs/shops, aggregated per
lot pair. They're assigned to shortest paths on the road graph, and travel time is updated with
a BPR curve (`t = t0 × (1 + 0.15 (v/c)^4)`). Iterate a few times (cheap incremental assignment).
Congestion feeds commute satisfaction and service response times, and drives visual car density.

---

## 6. Architecture (NeoForge 26.3)

### 6.1 Side split
- **Server:** all simulation, validation of every planner action (cost, permissions, tile
  ownership, terrain validity), block placement, saving.
- **Client:** planner camera and UI, ghost previews, overlays, visual traffic, ambient
  animation, Chirps.
- **Network:** payloads for planner actions (place road, paint zone, place service, set tax,
  …), city snapshot sync (on join and on change, delta-compressed), field tiles for overlays
  (sent only for info views in use), and notifications.

### 6.2 Persistence
- `CityData` as `SavedData` (codec-based). The road graph and lots are in the same or sibling
  SavedData. Player membership and progress as player attachments.
- Version every schema (a `dataVersion` field) for upgrades.

### 6.3 Block changes without loaded chunks
- A **construction queue** per chunk: jobs are lists of block placements generated from a
  structure (template or procedural). On a chunk-load event, pending jobs apply.
- Visible construction (when a player is near) runs incrementally for the animation. Unseen
  construction completes instantly on load.
- Forced chunk loading is **only** for active, player-watched construction, never for the
  whole city.

### 6.4 Buildings
- **Procedural growables:** a grammar for footprint (1×1 to 4×4 cells), floors by density and
  level, roof types, window rhythm, balconies and shopfronts, using a **style palette** (block sets
  per theme: Village Oak, Desert Sandstone, Modern Concrete, Nether Brick…) chosen per district.
  This gives effectively infinite variety at no art cost per building.
- **Template buildings:** `.nbt` structure templates (built in-game by you or creators) for
  services, unique buildings, monuments and signature growables. The data JSON declares
  footprint, entrance/road side, capacity and coverage.
- **Lot fitting:** foundations, grading and road-facing rotation.
- **Look (decided):** everything must look like it belongs in vanilla Minecraft, using the
  block vocabulary and shapes of **village structures**, even for high-density towers.
  Style palettes follow the five vanilla village biomes (plains oak, desert sandstone, savanna
  acacia, taiga spruce, snowy spruce/stone), picked from the district's biome. Shapes: log
  frames and corner posts, cobblestone/stone-brick bases, plank or terracotta walls, glass
  panes, stair-and-slab roofs, flower boxes, lanterns and bells. Taller buildings are stacked
  villager houses: a stone podium, timber-framed floors with jettied overhangs, stair-roofed
  setbacks and a lantern-lit crown. Commercial takes village workstations as storefronts
  (smithing tables, barrels, lecterns). Industry uses composters, blast furnaces, hay bales and
  mud-brick yards. Levels add height and detail, not a modern look.

### 6.5 Life on the streets
- **Ambient citizens:** a custom `CitizenNpc` entity that is non-persistent (despawns away from
  players) and spawned near players on sidewalks to match local density and time of day. It's
  bound to a real household record, so clicking or talking shows real data and lets you hear
  their complaints. Animated (walk, idle, chat). Capped at about 40 per player.
- **Visual traffic:** cars and buses are **client-side render-only objects** spawned by the
  client from road-segment flow values the server sends. They follow lane splines and yield at
  junctions with simple rules. No server entities and no collisions, so cost scales with view,
  not population.
- **Service vehicles:** the same visual approach, with dispatch decided abstractly.

### 6.6 Rendering
- Overlays and heat maps: `SubmitCustomGeometryEvent` with per-cell translucent quads,
  regenerated only when field tiles change.
- Ghost previews: translucent block-model submission. Coverage rings: line geometry.
- Status icons: billboard quads from a GUI-atlas sprite sheet.
- Planner HUD: GUI layers (`RegisterGuiLayersEvent`) plus a non-pausing planner screen for the
  toolbar and panels.

### 6.7 Data-driven content
```
data/<ns>/blocky_skylines/
  zones/*.json          road_types/*.json      services/*.json
  milestones/*.json     policies/*.json        unique_buildings/*.json
  building_styles/*.json (palettes + grammar parameters)
  structures/*.nbt      (templates)
```
Packs can add themes, services and road types without code, which is the CS Workshop lesson.

---

## 7. Scope guardrails

- **Not a traffic engineering sim.** Keep abstract flow plus visual cars. Lane arrows and
  priority signs are a possible later "TM:PE-lite", not core.
- **Not a real economy.** No CS2-style per-household money ledger. Aggregate, legible formulas.
- **Town scale**, not megacity: a mature city is around 5–20k (simulated) population on about
  500×500 blocks.
- **Performance budget is a feature:** a profiling HUD in dev builds, and strict per-tick budgets.

---

## 8. Roadmap

Each phase ends with something playable and screenshot-verified (headless client recipe in the
skill).

| Phase | Deliverable | "Done" looks like |
|---|---|---|
| **0. Foundation** | Project scaffold, Town Hall block, CityData, tiles, **Planner view** camera + cursor + empty toolbar, undo framework | Found a city, fly the planner camera smoothly, see tile borders |
| **1. Roads** | Road graph, 3 road types (small, medium, large), straight and curve tools, snapping, junction generation, terrain grading, bulldoze | Build a road grid and a curved avenue that look good in blocks |
| **2. Zoning & growth** | Zone grid, paint tools, RCIO demand v1, procedural houses/shops/factories (1–2 styles), construction animation | Zone a block, watch houses rise one by one |
| **3. Utilities & services** | Power and water (road-borne), 6 core services with coverage, status icons, info views (power, water, coverage), building panel | A building shows "no power", you place a plant, the icon clears |
| **4. People & money** | Households, jobs, education, happiness, land value/pollution/noise fields, leveling, taxes/budget/upkeep/loans | A balanced town grows, levels up and pays its bills |
| **5. Progression** | Milestones, unlocks, tile purchase, Chirps feed, first unique buildings | Hit "Worthy Village" and unlock a second tile (districts moved to Phase 7) |
| **6. Living city** | Ambient citizen NPCs, visual traffic, bus lines (coach lines), traffic flow model and info view | Walk your streets among citizens while coaches run routes |
| **7. Depth** | Districts and policies, specializations, disasters, monuments, multiplayer roles, more styles | A full play-through to "Megalopolis" |

**MVP / vertical slice = Phases 0–3 plus a minimal Phase 5** ("Little Hamlet" to "Worthy
Village"). That's the smallest thing that *feels* like Cities: Skylines in Minecraft.

---

## 9. Decisions (settled 2026-10-18)

1. **Mode: survival materials.** Construction consumes real items. See §10.
2. **Buildings: hybrid.** Procedural growables plus hand-made templates for services, unique
   buildings and monuments. All are generated by Claude in-repo.
3. **Scale: 8-block zone cells and 64-block tiles.**
4. **Loader: NeoForge 26.3.**
5. **Multiplayer: test single-player first.** The data model still allows several cities and
   co-mayors.
6. **Style: vanilla village look**, even for skyscrapers (§6.4).
7. **Name: Townstead** (mod id `townstead`). Avoid Paradox/Cities: Skylines names, logos and
   art in releases.
8. **Everything is real blocks.** Whatever the planner builds (roads, houses, shops, services,
   parks, utilities, transit) is placed in the world as ordinary Minecraft blocks, so after
   leaving the planner you can walk, mine and build in the city like anywhere else. Planner
   overlays (ghosts, borders, heat maps, icons) are view-only aids and never stand in for the
   build. Simulated values (population, demand, traffic) live in data, but every building they
   describe exists in the world. Building in unloaded chunks waits in the construction queue
   (§6.3) and finishes when the chunk loads, never as an abstract stand-in.

## 10. Survival supply design

The city is built from real items, so the mod plugs into normal Minecraft play instead of
replacing it.

- **Town Storehouse.** A block placed in the city (crafted early, near the Town Hall).
  Players deposit items. Linked **supply crates** (any chest with a crate upgrade) extend
  capacity. The storehouse inventory is the city's material stock, shown in the planner's top
  bar and in a storehouse screen.
- **Treasury in emeralds.** The treasury is a number backed by emeralds: deposit or withdraw
  at the Town Hall. Taxes accrue to it, and upkeep and tile purchases spend it. Emeralds are
  Minecraft's currency (villager trading), so this fits without a new coin.
- **Bill of materials.** Every plan (road segment, growable building, service) produces a
  material list from its block list, with similar blocks grouped (any planks, any logs, any
  stone bricks).
  - **Roads** reserve and consume materials when placed. If stock is short, the tool shows
    exactly what's missing in red and refuses.
  - **Growables** start building when demand exists. Construction pulls from the storehouse
    layer by layer. If something runs out, the site **pauses** with a floating "needs 12 oak
    planks" icon and a Chirp, then resumes when stock arrives.
  - **Services and unique buildings** show their full cost up front and wait for materials
    before breaking ground.
- **Closing the loop.** Early on, the player gathers materials by hand. Later, **industry
  zones and resource districts produce materials into the storehouse** (forestry yields logs,
  quarries yield stone, farms yield crops for food demand). Mid-game the city feeds itself,
  and the player's job shifts from gathering to planning, which mirrors Cities: Skylines'
  industry loop.
- **Deconstruction refunds** a share of materials (bulldozing returns about 50%; recently placed
  plans return 100%).
- **Creative/peaceful option:** a world setting `townstead:freeBuild` skips materials, for
  builders and for testing.

## 11. Phase 1 as built (roads)

- **Graph:** `RoadNetwork` (SavedData per dimension, `data/townstead/roads.dat`): nodes and segments.
  A segment is a quadratic Bezier centreline plus its graded heights (sampled once per block), so
  its blocks can be recomputed without reading the world. The client gets the whole graph for its
  dimension for snapping and previews.
- **Cross-sections:** lane `CCC` (3), street `SCCCS` (5), avenue `SCCCMCCCS` (9). C is dirt path,
  S stone bricks, M grass. Streets get lamp posts every 16 blocks on alternating walks; avenues
  get hedges every 4 and lamps every 16 in the median. Plains palette only for now; biome palettes
  come with growables.
- **Junctions:** a new road that crosses, starts on, or ends on an existing one splits it at that
  point. Where two columns overlap, carriageway beats median beats sidewalk, then the nearer
  centreline. Junctions of three or more roads, or of two at an angle, get a round paved cap so
  corners fill in.
- **Grading:** median ground height across the road width (ignores trunks and posts), smoothed,
  with a 1-in-2 grade limit; ends joining a road are pinned to it. Up to 12 blocks of fill (dirt,
  or cobblestone in water) and 16 of cut. Deeper dips and higher hills wait for bridges and
  tunnels.
- **Supply:** the Town Storehouse holds a virtual stock on the town (not a block inventory), so
  any storehouse of the town reaches it and it works with the storehouse chunk unloaded. Bills are
  per material group (item tags `townstead:materials/*`), and digging returns dirt, cobblestone
  and logs. Bulldozing refunds 100% within 5 minutes of building, 50% after.
- **Undo/redo:** every edit journals the blocks it changed (before/after), its road-graph diff and
  its stock delta. Undo restores all three; blocks changed by hand since are skipped and reported.
  32 entries per town, in memory for the session. Every later planner tool records the same way.
- **Not yet:** road upgrade tool, freeform (multi-bend) roads, bridges and tunnels.

## 12. Phase 2 as built (zoning and growth)

- **Lots:** the fixed 8×8 world cells. A cell is zonable when a road runs along one edge, either
  within 4 blocks outside it (a grass verge, crossed by a path from the door) or through its first
  two rows (the building is set back and kept shallower). Road deeper inside the cell rules it
  out. The road grid snap puts centrelines down the middle of cell rows, so a straight road
  consumes one row and leaves both sides zonable.
- **Demand (legible, per town):** households H (3 people each), shop jobs Jc, workshop jobs Ji,
  workers W = people/2. R = 40 + 3·(Jc+Ji−W), C = 6·(people/6 − Jc), I = 5·(people/5 − Ji) + 10,
  clamped 0–100. Sites under construction count, so the town doesn't overbuild. A zone needs 15 to
  grow; at most 2 sites per town at once; the free lot nearest the Town Hall goes first.
- **Buildings:** procedural from a seed (no stored block lists): cottages 5–6 × 5–6, 1–2 storeys;
  shops 6 wide with a workstation, shop window and striped awning; workshops 6 × 6 with a chimney
  campfire, hay and barrels. Plains-village palette (oak, spruce, birch). One block every 4 ticks,
  each costing one unit of its material group; doors place both halves; scaffolding rises beside
  the walls and comes down at the end. Lots are levelled first (felled logs go to the storehouse).
- **Simulation records:** `LandData` (per dimension) holds zones and buildings (zone, cell,
  frontage, floor height, seed, setback, progress, households/jobs, what a paused site needs).
- **Undo:** zoning strokes and building demolition go through the same history as roads.
- **Added after the first cut:** levels 1–3 with legible factors (Progression), rebuild-in-place
  upgrades that salvage what the new plan drops, abandonment after 10 minutes of trouble (no road,
  unemployment, no customers or workers) with boarded windows and cobwebs and repair on recovery,
  high-density homes and shops (from 30 people) and offices (from 40), five biome palettes,
  deterministic residents and employment (names, ages, trades after the villager professions) in
  a building panel, and offline construction: sites owe steps while unloaded and catch up by
  loading their chunks every 30 s; growth may open sites in unloaded chunks. A `growthSpeed`
  server setting scales build rate and timers.

## 13. Phase 3 as built (utilities and services)

- **Utilities are road-borne** (as in Cities: Skylines 2, which dropped pipes and pylons): road
  nodes are joined with union-find into networks, and a building belongs to the network of the
  road it faces. Each network pools the capacity of its active plants and wells: windmill 40
  power, watermill 80 (needs water beside the lot), well 30 water, water tower 90. Buildings are
  served oldest first (abandoned ones last); the rest go without. Demand per building: homes one
  per household, shops and offices one per two jobs, workshops one per job, services one.
- **Services cover a radius** from their lot: clinic 48, fire station 56, guard house 56,
  school 64, compost yard 80, park 32 blocks. Each is a real procedural building (palette by
  biome) with a few jobs that draw on the town's workers, so services also count as jobs for
  demand.
- **Levels now ask for them:** level 2 needs power and water; level 3 adds per-zone services
  (homes: clinic, school, park; shops: guard house, compost yard; workshops: fire station,
  compost yard; offices: guard house, school, clinic). A level-2+ building that loses power or
  water is in trouble and is eventually abandoned.
- **One pure function** (`Utilities.compute`) feeds the server's level and trouble checks and
  the client's status icons, info views and building panel, so what the player sees is what the
  simulation decided.
- **Planner:** the Utilities, Services and Parks tools place a chosen building on a road-facing
  lot (ghost, coverage ring, bill of materials, reason when it can't go there); the Info tool
  colours buildings by power, water, each service's coverage or level. Status icons over roofs
  are projected through the camera's near plane. Placing goes through undo like everything else;
  zoning strokes skip service lots.
- **Deferred to Phase 4:** upkeep and budgets for services, service capacity (patients,
  pupils), garbage and fire as events, and land value effects of parks.

## 14. Buildings, revised (after playtest feedback)

Feedback: buildings were all one size and grew upward; the town looked stacked and basic. Changes:

- **Lots of 1–4 cells.** A building's lot is 1×1, 2×1 (along the road), 1×2 (deep) or 2×2 cells.
  Each zone draws a lot shape by weight (homes mostly 1×1 and 2×1; high density, markets,
  industry and offices mostly 2×1 and 2×2) and falls back to smaller lots. Services have fixed
  footprints (school 2×2; clinic, fire station, compost yard, park, watermill 2×1). Cells right
  behind a road-facing cell are zonable for deep lots.
- **Composed architecture** (`BuildingGenerator`): buildings are masses (main block, wings, bays,
  lean-tos) with timber framing on a stone plinth, symmetric windows (tall on the ground floor,
  flanking doors), open-trapdoor shutters, jettied upper floors on brackets, gable roofs with
  eaves and attic windows, hipped roofs, dormers, brick chimneys with smoke, porches, door hoods,
  and lots dressed with hedges, flowers, paths, fences and gates, trees, vegetable plots, lamps
  and benches. Plains and taiga towns mix roof colours (dark oak, spruce, brick).
- **Build order and shapes.** Steps go ground work, frame, roof, fittings, garden, so lanterns and
  torches always have something to hang on; each placed block takes its shape from its
  neighbours (fences and panes join up, stair corners turn).
- **Levels spread out:** wings, porches, lean-tos, dormers and gardens come with levels, not just
  storeys.
- A **Bricks** material joins the storehouse groups (chimneys, fire stations, brick roofs).

## 15. Phase 4 as built (people and money)

- **One model of the town** (`sim.TownModel`) is built from the buildings, roads and budget: power
  and water (with department budgets scaling plant output), service reach (budget scales radius:
  80% at a 50% budget, 120% at 150%), the land fields, who is schooled, who works where, demand
  and happiness. The server uses it to decide (growth, levels, trouble, taxes); the client builds
  the same one to explain (panel, info views, budget, Town Hall).
- **Money** in hundredths of an emerald. Settlement every 600 ticks applies a fortieth of the
  daily figures (scaled by `growthSpeed`) and records a ledger entry. Taxes: households ×
  4 emeralds × level factor (1, 1.5, 2.25) × land-value factor (0.8 + value/250), filled jobs ×
  5/4/7 emeralds (shops/workshops/offices) × level factor, times the rate. Upkeep per service at
  its department budget; roads 1/2/4 hundredths per block of lane/street/avenue a day; loans
  repaid daily. Debt halves service strength. The Town Hall holds emeralds; withdrawals keep a
  25-emerald founding reserve and anything owed on loans, so loans can't be cashed out.
- **Education** per resident (0–3, from the seed: 30/45/20/5%); a school in reach adds one.
  Employment fills skilled jobs first (offices, clinic, school need level 2). Office demand
  follows well-educated adults. Taxes push all demand three points per percent from 9%.
- **Happiness** (0–100) = 50 + factors: power/water (+5, or −5/−15 missing at level 1/2+),
  services in reach (+5, park +8), land value ((v−50)/5), pollution (−p/3) and noise (−n/5) at
  homes and offices, taxes ((9−rate)×2), unemployment over 10%, short staff, customers, debt.
- **Fields** per cell from point sources with linear falloff: pollution (workshops 30+, compost
  yard 25), noise (shops 12–20, workshops 15, fire station 12, roads by type, loudest stretch),
  land value (base 30, Town Hall +12, parks +25, services +6, shops +4, minus 0.6·pollution and
  0.4·noise).
- **Levels** now also need happiness (50 / 65), land value 50 at level 3 for homes and shops, and
  20 well-educated adults for level-3 offices. Trouble adds "unhappy" (with its worst factor) and
  offices short of educated staff.
- **UI:** budget panel (L / treasury click), treasury with daily net and town happiness in the
  top bar, info views for happiness, land value, pollution, noise and education (heat maps for
  the fields), building panel sections for happiness factors, the lot's fields and taxes, and
  each resident's education; Town Hall treasury screen.
- **Deferred:** ageing, births and deaths, crime, garbage and fire as events, service capacity.


## 16. Phase 5 as built (progression)

- **Milestones** (`progress.Milestone`): Little Hamlet (start), Worthy Village (40 people),
  Tiny Town (100), Boom Town (200), Busy Town (350), Big Town (550). Each lists the road types,
  zones and services it unlocks, its emerald reward and its extra land allowance (+1, +2, +3, +4,
  +6 tiles on top of the starting 3×3). The economy settlement checks population against the next
  milestone (`progress.Milestones`); reaching it pays the reward, shows a title and subtitle with
  the challenge-complete sound, notifies the mayor and posts a chirp. A town never loses a
  milestone. The synced setting `unlockAll` skips the gates.
- **Unlocks are enforced on the server** (road builds, zone painting, service placement refuse
  locked things with "X unlocks at Y") and **explained on the client**: locked sub-tools are
  greyed with a cross and an "Unlocks at Worthy Village (40 people)" tooltip, and picking one
  flashes the reason. The top bar shows progress to the next milestone; **U** opens the milestone
  panel (each title, its people, reward and land, icons of what it unlocks, a progress bar).
  Districts, which the roadmap first tied to Worthy Village, moved to Phase 7 with policies.
- **Land** (`city.TownActions.buyTile`): the Land tool outlines buyable tiles in gold (dim gold
  when the treasury or the milestone allowance falls short, red when too close to another town)
  and fills the hovered one. A tile must border the town's land and keep the usual gap from other
  towns. Price: 40 emeralds × (tiles owned − 8), so the tenth tile costs 40 and each one after
  costs 40 more. Bought land is claimed at once and is zonable like the rest.
- **Chirps** (`progress.Chirps`): short posts signed by a real resident (picked from the town's
  households), two or three text variants per event: moved in, opened, levelled up, trouble
  starting, abandoned, repaired, new service, landmark, milestone, land bought, broke, solvent.
  Routine news is limited to one per 20 seconds per town; milestones, landmarks, abandonment,
  land and debt always get through. The planner shows the latest three for 15 seconds in the top
  right with a chime. With Inspect or Info, clicking one flies the camera to the place it's about;
  with an editing tool they let clicks through and fade under the cursor (the first playtest had
  a chirp swallow the end click of a road).
- **Landmarks**, the first unique buildings: Founders' Fountain (2×1 lots, radius 48, Worthy
  Village), Clock Tower (1×1, radius 80, Boom Town) and Chapel (2×2, radius 64, Busy Town). One
  per town, placed with the Landmarks tool, built block by block like services, staffed by a
  keeper and a gardener, upkeep from the parks department. Each adds land value (+22 at the
  centre) and +6 happiness to every building in reach.

## 17. Phase 6 as built (living city)

- **Routing** (`road.RoadRouter`): Dijkstra over the town's junction graph from a point on one
  segment to a point on another, leaving and entering segments part-way. Shared by traffic, coach
  lines and the vehicles drawn on the streets. Speeds: lane 1.0, street 1.3, avenue 1.8.
- **Traffic** (`sim.Traffic`, part of the shared `TownModel`): trips are commutes (each filled job,
  home to workplace), errands (half a trip per household to the nearest shop) and deliveries (0.3
  per workshop job to the nearest shop). Identical trips merge; each is assigned a quarter at a
  time over four rounds, every round routing on travel times that include the previous rounds'
  congestion (BPR: t = t0 × (1 + 0.15 (v/c)^4)). Capacities per day: lane 40, street 100, avenue
  260. Outputs: flow and v/c per segment, each home's commute delay, and the town's *traffic flow*
  (free-flow time over actual time, as in Cities: Skylines).
- **Effects:** homes whose commutes take over 25% longer lose up to 12 happiness ("Stuck in
  traffic"); road noise now scales with load (40% of the old figure on an empty road, up to 160%
  jammed); the traffic info view colours roads by v/c and reads out a hovered road's trips.
- **Coach lines** (`transit.CoachLine`, `transit.CoachLines`): loops of 2–24 stops drawn with the
  Transport tool, up to 8 per town, 0.5 emerald a stop. Stops are real blocks: a spruce post with a
  standing banner in the line colour and a spruce-stair bench, removed with the line. Stops keep
  their post position, so a road split by a new junction or rebuilt is found again by position.
  The **Coach House** (2×1 lot, a timber stable with two coach doors and a paddock; unlocked at
  Worthy Village; new Transport department) is needed for coaches to run. A line runs one coach
  per 90 blocks of route at a 100% budget. A commuter rides when home and work are each within 40
  blocks of different stops of a running line (60% of them, a stable share by name, scaled by the
  budget); riders add no car trips, coaches add four trips each along the route, riders pay 4
  hundredths a day, coaches cost 1.2 emeralds a day.
- **Street life** (`client.StreetLife`): client-only puppets (vanilla horse/donkey/mule, minecart
  and villager entities that are never added to the world, given negative ids and drawn through
  the entity render dispatcher from `ExtractLevelRenderStateEvent` / `SubmitCustomGeometryEvent`).
  Carts spawn on roads within 112 blocks of the camera, picked by flow, about one per six trips
  a day per 64 blocks, and wander the graph turning by flow; they keep right (0.8 blocks off the
  centreline, 2 on avenues). Coaches (two horses abreast, a minecart with a carpet canopy in the
  line colour, two villager passengers) run evenly spaced round each line and dwell 2.5 s at every
  stop. Drawing failures are caught and logged once, never crash the game.
- **Citizens** (`life.CitizenNpc`, `life.Citizens`): a non-saved `AgeableMob` with the villager
  model, dressed by town style (villager type) and trade (profession; plain clothes for children
  and the jobless). Around each player in a town, up to a quarter of the population (max 24) are
  out by day and a quarter of that at night, spawned 12+ blocks away on sidewalks (lane verges),
  each a real person from a home within 48 blocks, never two copies of one person. They stroll
  between street points, look at players, and on right-click say their name and age, home, job
  (or school, retirement, job hunting) and mood with their home's biggest complaint or joy.
- **Deferred:** pedestrian trips and walking in the model, service vehicles, minecart metro,
  per-lane junction behaviour.

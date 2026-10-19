# Townstead

*From hamlet to metropolis.* A Minecraft city-builder mod inspired by Cities: Skylines. Plan
from above, and the town grows itself in the style of Minecraft villages. Then walk the streets
you designed.

**Minecraft 26.3 · NeoForge 26.3.0.90 · Java 25** · Status: **Phase 2 complete (zoning, growth, levels)**

## Playing (current build)

1. Craft a **Town Hall**: an emerald on top, a **bell** in the middle with planks on each side,
   and logs along the bottom. You found a town by bringing a village bell home.
2. Place it. Your town claims the 3×3 land tiles around it (64×64 blocks each). Towns need
   a little space between them.
3. Press **M** to open the **Town Planner**: an overhead view of your land. The controls follow
   Cities: Skylines:

   | Action | Keys / mouse |
   |---|---|
   | Pan | **WASD** or arrows (hold **Shift** to go faster) |
   | Rotate | **Q / E**, or hold the **middle mouse button** and drag |
   | Zoom | **scroll**, **Z / X** (or **R / F**) |
   | Tilt | **Home / End** (or **T / G**), or middle-drag up/down |
   | Select a zone cell | **left-click** |
   | Cancel | **right-click** or **Esc** (selection first, then the tool) |
   | Bulldoze | **B** |
   | Rename the town | click its name in the top bar, type, **Enter** |
   | Show all controls | **H** |
   | Zone lots | Zoning tool, then drag; **1–6** zone type, **7** clear |
   | Place a service | Utilities / Services / Parks tool, **1–5** pick, click a lot |
   | Info views | Info tool, **1–9** pick a view (or click) |
   | Town budget | **L**, or click the treasury |
   | Hide status icons | **V** |
   | Building panel | Inspect tool, click a building (Esc closes) |
   | Undo / redo | **Ctrl+Z** / **Ctrl+Y** (or **Ctrl+Shift+Z**) |
   | Leave the planner | **M**, or **Esc** with nothing selected |

   Green lines mark your town's land. The cursor highlights 8×8-block zone cells. Edge scrolling
   can be turned on in the mod's config screen. `/townstead rename <name>` also works.
4. Craft a **Town Storehouse** (planks on top, chest · barrel · chest in the middle, logs along
   the bottom) and place it on your town's land. Use it with a building material in hand to store
   that stack, sneak-use to store everything you carry, or use it empty-handed to see the stock
   and take items back. Everything the planner builds comes out of this stock.
5. **Roads** (toolbar, second button). Pick a size and a mode in the panel above the toolbar:

   | Road | Width | Built from |
   |---|---|---|
   | Lane | 3 | dirt path |
   | Street | 5 | dirt path with stone-brick walks and lamp posts |
   | Avenue | 9 | two dirt-path carriageways, stone-brick walks, a grass median with hedges and lamps |

   Click to start, click to finish; a curved road takes a middle click to set the bend. The next
   road starts where the last one ended; right-click or Esc stops. **1/2/3** pick the size, **Tab**
   switches straight/curved, hold **Ctrl** to turn snapping off. Ends snap to existing roads and
   junctions, to an 8-block grid, and to 15° angles. Crossing a road makes a junction
   automatically. Roads follow the ground (smoothed, at most a 1-in-2 grade), fill dips and cut
   through small hills. The ghost shows the road before you build it, blue when it's buildable,
   orange when the storehouse is short and red when there's a problem; the panel by the cursor
   lists the materials it needs against what you have.
6. **Bulldoze** (B, or the last toolbar button) removes a road piece. Materials come back in
   full if you built it in the last five minutes, half after that.
7. **Undo / redo**: **Ctrl+Z** undoes the last planner edit (build or bulldoze) and puts back
   every block, the road network and the storehouse stock exactly as they were. **Ctrl+Y** or
   **Ctrl+Shift+Z** redoes it. Blocks you've changed by hand since are left alone. The last 32
   edits per town are kept until the world is closed.

Costs: one dirt per dirt-path or grass block, one stone brick per walk block, dirt or
cobblestone (in water) for fill, two fences and a lantern per lamp post, one leaves block per
hedge. Digging returns dirt, cobblestone and logs to the storehouse. Creative-mode players build
for free, and the server config option `freeBuild` turns materials off for everyone.

8. **Zoning** (toolbar, third button). Pick a zone and drag over lots: **1** low-density
   homes, **2** high-density homes, **3** low-density shops, **4** high-density shops,
   **5** industry, **6** offices, **7** clears. Lots are the 8×8 cells along a road: a lot
   needs a road along one side, within 4 blocks of its edge. Outlined lots are zonable.
   High density grows once the town has 30 people, offices from 40.
9. **The town grows itself.** The RCIO bars in the top bar show what the town wants (hover for
   the reasons): homes follow jobs, shops and workshops follow population, offices follow a
   bigger population. When a zone has demand, a site opens on the free lot nearest the Town
   Hall and the building goes up **one block at a time** from the Town Storehouse, with
   scaffolding. If a material runs out, the site pauses and says what it needs.

   Buildings take whole lots of one to four cells (one lot along the road, two side by side, two
   deep, or a block of four), so streets mix small cottages with farmhouses and big halls. Cells
   right behind a road-facing cell can be zoned too, for deep lots.

   | Zone | Builds | Look |
   |---|---|---|
   | Homes (low) | cottages, houses with back gardens, farmhouses, manors | timber-framed walls on a stone plinth, shuttered windows, gable roofs in mixed colours, jettied upper floors, dormers, brick chimneys, porches, hedges, flower gardens, vegetable plots, trees, fences and gates |
   | Homes (high) | terraces, townhouses, courtyard blocks | rows of narrow houses with their own doors, wall colours and roof heights; three ranges round a shared garden with a gateway |
   | Shops (low) | shops, inns | glazed shop fronts under striped awnings, trade banners, crates out front; inns with a cafe terrace of tables and chairs |
   | Shops (high) | market halls, market squares | stone arcades of stalls under a hall, a bell cupola; a paved square with stalls, a fountain and trees |
   | Industry | workshops, works | stone ground floor and timber loft, wide doors, a forge with a tall smoking chimney, a fenced yard of timber, hay and barrels, a warehouse |
   | Offices | counting houses, guild halls | stone ground floor with tall windows, steps to double doors, banners, hipped roof and cupola; a corner tower and garden on a big lot |

   **Biome styles:** each building takes the village style of its lot's biome: plains (oak and
   birch), desert (sandstone, flat roofs), savanna (acacia), taiga (spruce, mossy stone) or
   snowy (spruce and stone bricks). A town that spreads across biomes changes style at the
   border.
10. **Levels.** Buildings have levels 1–3. When a building meets everything its next level
    needs, it is rebuilt bigger in place: cottages gain a jettied upper floor and a lean-to, then
    a chimney, dormer and lamp; farmhouses a porch; terraces a storey; shops a flat above; halls
    a cupola. What a building
    needs is plain and checkable: time to settle in, a shop nearby for homes, customers nearby
    for shops, shops to sell to for workshops, a big enough town for offices, and a street or
    avenue for level 3. Level 2 also needs power and water; level 3 needs services in reach
    (see below).
11. **Abandonment.** A building with lasting trouble (no road, no jobs in town, no customers,
    no workers, or lost power or water at level 2 and up) is abandoned: windows boarded, cobwebs, the door gone, nobody living or
    working there. Fix the cause and people come back and repair it.
12. **Building panel.** Click a building with Inspect to see its name ("Miller Cottage",
    "Thatcher's Smithy"), level and style, what its next level needs (ticked or crossed), any
    trouble, and its people: every household with names and ages, where each adult works or
    if they're looking for work; or a workplace's staff and where they live.
13. **While you're away** towns keep growing: sites in unloaded chunks keep count of the
    blocks they owe and build them when the chunk next loads, or every 30 seconds by loading
    it briefly. New sites can open anywhere on town land.

14. **Utilities** (toolbar, fourth button): **1** windmill (40 power), **2** watermill (80 power,
    needs a river or lake beside its lot), **3** well (30 water), **4** water tower (90 water).
    Power and water run along the roads: every building on a connected road network shares the
    plants and wells on it. When a network asks for more than it has, the oldest buildings are
    served first and the rest go without. Homes use one unit per household, shops and offices one
    per two jobs, workshops one per job.
15. **Services** (fifth button): **1** clinic, **2** fire station, **3** guard house, **4** school,
    **5** compost yard; **Parks** (seventh button): a park. Each covers the buildings within its
    radius (32–80 blocks); the ring shows it while you place. Level 3 needs: homes a clinic, a
    school and a park; shops a guard house and a compost yard; workshops a fire station and a
    compost yard; offices a guard house, a school and a clinic.

    Pick one, point at a lot facing a road (green: it fits; red: the panel says why) and click.
    The bill of materials shows by the cursor. Services are built block by block like everything
    else, take staff from the town's workers, and can be undone or bulldozed.
16. **Status icons and info views.** Buildings show what they're missing over their roofs
    (redstone: no power; water bucket: no water; cobweb: abandoned; a material: a site waiting on
    it). The **Info** tool (eighth button) colours the town: **1** power, **2** water, **3–8** each
    service's coverage (with rings), **9** building levels, with totals in the corner.

17. **Money.** The treasury is in emeralds. Use the Town Hall with emeralds (or emerald blocks)
    in hand to put them in; use it empty-handed to see the treasury and take emeralds out. A new
    town starts with a 25-emerald purse, which stays as a reserve, and you can't take out what's
    still owed on loans. Every half minute the town collects a slice of its daily taxes and pays a
    slice of its upkeep.
18. **The budget** (**L**, or click the treasury in the top bar). Taxes per kind of building
    (homes, shops, workshops, offices) from 0 to 29%: each household and each filled job has a
    taxable value that grows with level (and, for homes, land value). Above 9% people get less
    happy and demand falls; below it the opposite. Every service department has a budget from 50%
    to 150%, which scales its upkeep and its reach (or a plant's output). Roads cost upkeep by
    length. Three loans are on offer (50, 150 and 400 emeralds at 5–12% interest), paid back
    daily or early. A town in debt runs its services at half strength. The bars show recent
    settlements.
19. **People.** Every resident has an education level (unschooled to highly educated); a school in
    reach of their home adds one. Offices, the clinic and the school need well-educated workers,
    and offices only come when the town has them. **Happiness** comes from plain factors listed in
    the building panel: power and water, services in reach, a park, land value, pollution, noise,
    taxes, unemployment, staff and customers. Level 2 needs 50, level 3 needs 65, and below 20 it's
    trouble. **Pollution** (workshops, the compost yard), **noise** (shops, workshops, the fire
    station, busy roads) and **land value** (parks, services, shops, the Town Hall; pollution and
    noise lower it) each have an info view. Level 3 homes and shops need land value 50.

The server setting `growthSpeed` (default 1) makes towns build, level and decline faster or
slower.

Breaking the Town Hall disbands the town. Milestones, unlocks and buying land come next
(Phase 5). See the roadmap.

## Screenshots (from the headless playtests)

Buildings, revised: lots of one to four cells, timber framing, mixed roofs, gardens and landmarks.

| | |
|---|---|
| ![A town of mixed lots and services](docs/screenshots/buildings-town.png) | ![Shops, inns, terraces and a market hall](docs/screenshots/buildings-shops-row.png) |
| ![Cottages with jettied upper floors, dormers and hedges](docs/screenshots/buildings-cottages.png) | ![A market square with stalls, a fountain and a bell cupola](docs/screenshots/buildings-market-square.png) |
| ![Desert houses with roof terraces and cloth canopies](docs/screenshots/buildings-desert.png) | ![Windmill, well and water tower](docs/screenshots/buildings-windmill-well-tower.png) |

Phase 4: the town budget, the Town Hall treasury, the land value info view, and a building
panel explaining happiness factor by factor.

| | |
|---|---|
| ![Budget: taxes, upkeep by department, roads, loans](docs/screenshots/phase4-budget.png) | ![Town Hall treasury](docs/screenshots/phase4-town-hall.png) |
| ![Land value heat map](docs/screenshots/phase4-land-value.png) | ![Building panel: happiness factors](docs/screenshots/phase4-panel-happiness.png) |

Phase 3: homes show they have no power or water; a windmill and a well go in and the icons
clear, and the first cottage is rebuilt at level 2.

| | |
|---|---|
| ![Status icons: no power, no water](docs/screenshots/phase3-no-power.png) | ![After the windmill and well: icons gone, a cottage levelling up](docs/screenshots/phase3-powered.png) |
| ![Placing a clinic: ghost, coverage ring and bill of materials](docs/screenshots/phase3-place-clinic.png) | ![Clinic coverage info view](docs/screenshots/phase3-clinic-coverage.png) |
| ![Building panel: level 3 needs, ticked](docs/screenshots/phase3-panel.png) | ![A park between cottages](docs/screenshots/phase3-park.png) |

Levels, styles and abandonment: biome styles side by side (taiga, savanna, desert), shops and
offices, and a workshop abandoned after its road was removed.

| | |
|---|---|
| ![Taiga, savanna and desert homes](docs/screenshots/phase2-styles.png) | ![Market hall, guild hall and shops](docs/screenshots/phase2-styles-b.png) |
| ![An abandoned workshop: door gone, cobwebs](docs/screenshots/phase2-abandoned.png) | |

Phase 2: zoned lots grew into a village street, built block by block from the storehouse.

| | |
|---|---|
| ![A grown street: shop, workshop, cottages](docs/screenshots/phase2-street.png) | ![A cottage](docs/screenshots/phase2-cottage.png) |
| ![A workshop with a smoking chimney](docs/screenshots/phase2-workshop.png) | ![Zoned lots and two construction sites from the planner](docs/screenshots/phase2-sites.png) |

Phase 1: roads.

| | |
|---|---|
| ![Drawing a curved avenue: ghost and bill of materials](docs/screenshots/phase1-avenue-preview.png) | ![Street, avenue and lane with junctions](docs/screenshots/phase1-junctions.png) |
| ![Avenue at street level](docs/screenshots/phase1-avenue-street-level.png) | ![Town Storehouse](docs/screenshots/phase1-storehouse.png) |

## Building

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew build        # jar in build/libs
./gradlew runClient    # dev client
./gradlew runServer    # dedicated server
```

## Docs

- [Cities: Skylines research](docs/research/cities-skylines.md): how the original games work.
- [Design & roadmap](docs/design/minecraft-adaptation.md): how Townstead adapts each system,
  the decisions taken, the survival-supply design and the phased plan.
- `.claude/skills/minecraft-modding/`: the 26.x modding skill used to build this mod.

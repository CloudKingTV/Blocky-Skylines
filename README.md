# Townstead

*From hamlet to metropolis.* A Minecraft city-builder mod inspired by Cities: Skylines. Plan
from above, and the town grows itself in the style of Minecraft villages. Then walk the streets
you designed.

**Minecraft 26.3 · NeoForge 26.3.0.90 · Java 25** · Status: **Phase 7 complete (districts and policies, specialisations, fires, monuments, co-mayors, jungle and swamp towns, milestones to Megalopolis)**

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
   | Place a service | Utilities / Services / Parks / Landmarks tool, **1–5** pick, click a lot |
   | Buy land | Land tool, click a gold-outlined tile |
   | Coach lines | Transport tool: **1** Coach House, **2** draw a line (click stops, **Enter** or the first stop to finish), **3** remove a line |
   | Milestones and unlocks | **U** |
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
   High density and offices unlock with milestones (see below).
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
    it). The **Info** tool (tenth button) colours the town: power, water, happiness, land value,
    pollution, noise, education, traffic, building levels and each service's coverage (with
    rings), with totals in the corner.

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

20. **Milestones.** The town earns titles as it grows, as in Cities: Skylines. Each pays a
    purse of emeralds into the treasury, allows more land, and unlocks new things to build. The
    top bar shows progress to the next one; **U** lists them all with what each unlocks. Locked
    roads, zones and buildings are greyed out in the tool panels with the milestone that unlocks
    them.

    | Milestone | People | Reward | Land | Unlocks |
    |---|---|---|---|---|
    | Little Hamlet | 0 | | 3×3 tiles | lane, street; homes, shops, industry; windmill, well, park |
    | Worthy Village | 40 | 40 emeralds | +1 tile | avenue; clinic, school, guard house; Founders' Fountain |
    | Tiny Town | 100 | 80 | +2 | dense homes and shops; fire station, compost yard, water tower |
    | Boom Town | 200 | 150 | +3 | offices; watermill; Clock Tower |
    | Busy Town | 350 | 250 | +4 | Chapel |
    | Big Town | 550 | 400 | +6 | |
    | Small City | 800 | 600 | +6 | Great Library |
    | Big City | 1100 | 800 | +6 | |
    | Grand City | 1500 | 1000 | +8 | Cathedral |
    | Capital City | 2000 | 1300 | +8 | |
    | Colossal City | 2600 | 1600 | +10 | |
    | Metropolis | 3300 | 2000 | +10 | Castle Keep |
    | Megalopolis | 4000 | 3000 | +12 | |

    The server setting `unlockAll` opens everything from the start.
21. **Buying land** (Land tool, ninth button). Tiles your town may buy are outlined in gold:
    a tile must border your land, keep its distance from other towns, and fit the allowance
    from your milestones. Hover one for its price (40 emeralds for the tenth tile, then 40 more
    for each one after) and click to buy it from the treasury.
22. **Chirps.** Residents post about what happens in town: people moving in, shops opening,
    buildings levelling up, trouble, abandonment, new services, milestones, new land, debt. The
    latest show in the top-right corner of the planner, signed by a real resident; with the
    Inspect or Info tool, click one to fly the camera there.
23. **Landmarks** (eighth button): unique buildings, one per town. The **Founders' Fountain**
    (Worthy Village), **Clock Tower** (Boom Town) and **Chapel** (Busy Town) raise land value
    and make everyone in reach happier (+6), and cost upkeep from the parks budget.

24. **Traffic.** Everyone who works drives to work, households run errands to the nearest shop
    and workshops deliver to shops. These trips are routed over your road network and pile up
    on its roads: lanes carry about 30 trips a day before they slow, streets 80, avenues 200. A
    single lane joining two busy streets jams by around 70 people; carts on a jammed road crawl.
    The **traffic info view** (Info tool, **8**) colours every road green, yellow or red and
    shows the town's *traffic flow* (100% means nobody is held up). Jammed commutes make people
    unhappy, and busier roads are louder.
25. **Coach lines** (Transport tool, sixth button), the village answer to bus lines. Build a
    **Coach House** (**1**, unlocked at Worthy Village), then pick **2** and click along your roads
    to drop stops; click the first stop again or press **Enter** to close the loop. Each stop
    goes up as a real stop by the road: a post flying a banner in the line's colour, with a bench.
    People who live and work near stops of the same line ride it instead of driving: that takes
    trips off the roads and earns fares, while the coaches cost upkeep from the new Transport
    budget line. Homes near a stop are a little happier. The panel at the top left lists each
    line's stops, coaches, riders and length; **3** removes a line and its stops. Bulldozing a
    road takes its stops down too.
26. **Carts and coaches on the streets.** Horse, donkey and mule carts loaded with hay, barrels,
    logs and produce drive the roads, more on busier ones, keeping to the right. Coaches in
    their line's colours (two horses, passengers aboard) run every line and wait at each stop.
    They're drawn on your screen only, so they cost the server nothing.
27. **Citizens.** Real residents walk the sidewalks around you, dressed for their trade and in
    their town's style, more in a bigger town and fewer at night. Look at one to see their name;
    right-click to hear who they are, where they live and work, how happy they are and what's on
    their mind.

28. **Districts** (Districts tool, eleventh button). **1** starts a new district and **2** paints
    more cells into the selected one (drag, like zoning), **3** erases, **4** selects. Each district
    gets a name and colour, shown on the map while the tool is open. Selecting one opens its panel:
    size and people, its specialisation and its own policies.
29. **Policies.** **P** opens the town's policies; a district's panel sets its own on top. Clean
    Hearths (less smoke and fewer fires from workshops), Quiet Nights (quieter, a little less shop
    tax), Market Days (busier shops), Lantern Streets and Garden Town (land value, happier homes),
    Old Town (charm instead of growth past level 2), Craftsmen's Relief (half tax for workshops),
    Evening Classes (schooling for every home) and Free Coaches (town-wide: no fares, more riders).
    Some cost a little each day; the budget shows them on a *Policies* line.
30. **Specialisations.** In a district's panel, make its industry **Farming** (barns, crop fields,
    granaries: almost no pollution) or **Forestry** (saw sheds, log stacks, saplings), or its shops
    **Leisure** (taverns with beer gardens: louder, more tax). New lots there grow in that shape,
    and their workers are farmers, woodcutters, sawyers, innkeepers.
31. **Fires.** From Tiny Town on, buildings now and then catch fire: smoke you can see from the
    planner, flames at the windows, the bell ringing and residents posting about it. Inside a fire
    station's reach the brigade puts it out; outside, the building burns to a charred shell and the
    fire can spread next door. Shells stand empty until the area is doing well, then are rebuilt from
    the storehouse. Workshops, lumber yards and taverns burn more easily. The server setting
    `fires` turns them off; `/townstead ignite` (operators) starts one for testing.
32. **City milestones and monuments.** The milestones go on past Big Town to **Megalopolis** at
    4000 people (table above). Three of them unlock **monuments**, the grandest landmarks: the
    **Great Library** (schools every home in its reach), the **Cathedral** (pilgrims fill the
    shops) and the **Castle Keep** (its garrison guards the town and fights fires across a huge
    reach). Each raises land value and happiness for a long way around.
33. **Co-mayors.** `/townstead comayor add <player>` lets a friend help run your town: they open
    the planner on it, build, zone, set policies and budgets, use the storehouse and get the town's
    news. Loans, the treasury, the town's name and co-mayors stay with you. `/townstead comayor
    remove <name>` and `/townstead comayor` (list).
34. **Jungle and swamp towns.** Lots in jungles build in jungle and bamboo timber under bamboo
    roofs with mossy stone; lots in swamps and mangroves in mangrove timber on mud-brick footings.

Breaking the Town Hall disbands the town.

## Screenshots (from the headless playtests)

Phase 6: a jammed lane in the traffic info view and the same town after a second street,
drawing a coach line and the line on the map, a coach with its
passengers, a hay cart, a stop by the road, the budget with fares and a Transport line, and a
citizen who says who they are.

| | |
|---|---|
| ![Traffic info view: a jammed lane (red) carrying all cross-town traffic, flow 83%](docs/screenshots/phase6-traffic.png) | ![After a second street: flow back to 94%](docs/screenshots/phase6-traffic-relieved.png) |
| ![Drawing a coach line: stops and route](docs/screenshots/phase6-line-draft.png) | ![Carts on the busy lane](docs/screenshots/phase6-lane-carts.png) |
| ![The Red line on the map, with its numbers](docs/screenshots/phase6-lines.png) | ![A coach: two horses and passengers](docs/screenshots/phase6-coach.png) |
| ![A mule cart with hay, keeping right](docs/screenshots/phase6-cart.png) | ![A coach stop: post, banner and bench](docs/screenshots/phase6-stop.png) |
| ![Budget: coach fares and Transport upkeep](docs/screenshots/phase6-budget.png) | ![A citizen: home, work and mood](docs/screenshots/phase6-citizen.png) |

Phase 5: reaching Worthy Village (the planner's milestone banner), the milestone panel (U),
buying a tile with the Land tool, locked services, and the first landmark: the Founders'
Fountain, announced by a resident's chirp.

| | |
|---|---|
| ![Milestone reached: Worthy Village](docs/screenshots/phase5-milestone-banner.png) | ![Milestones and what each unlocks](docs/screenshots/phase5-milestones.png) |
| ![Buying a tile: price and allowance](docs/screenshots/phase5-buy-land.png) | ![Services locked until Worthy Village](docs/screenshots/phase5-locked.png) |
| ![A chirp about the new fountain](docs/screenshots/phase5-chirp-fountain.png) | ![The Founders' Fountain](docs/screenshots/phase5-founders-fountain.png) |

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

# Townstead

*From hamlet to metropolis.* A Minecraft city-builder mod inspired by Cities: Skylines. Plan
from above, and the town grows itself in the style of Minecraft villages. Then walk the streets
you designed.

**Minecraft 26.3 · NeoForge 26.3.0.90 · Java 25** · Status: **Phase 1 (roads)**

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

Breaking the Town Hall disbands the town. Zoning, growth, services and citizens come next. See
the roadmap.

## Screenshots (Phase 1, from the headless playtest)

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

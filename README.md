# Townstead

*From hamlet to metropolis.* A Minecraft city-builder mod inspired by Cities: Skylines. Plan
from above, and the town grows itself in the style of Minecraft villages. Then walk the streets
you designed.

**Minecraft 26.3 · NeoForge 26.3.0.90 · Java 25** · Status: **Phase 0 (foundation)**

## Playing (current build)

1. Craft a **Town Hall**: an emerald on top, a **bell** in the middle with planks on each side,
   and logs along the bottom. You found a town by bringing a village bell home.
2. Place it. Your town claims the 3×3 land tiles around it (64×64 blocks each). Towns need
   a little space between them.
3. Press **M** to open the **Town Planner**: an overhead view of your land.
   - **WASD** pan · **Q/E** or right-drag to rotate · **scroll** to zoom · **left-click** to
     select a zone cell · **Esc** or **M** to return to where you were standing.
   - Green lines mark your town's land. The cursor highlights 8×8-block zone cells.
4. Breaking the Town Hall disbands the town.

Roads, zoning, construction from your Town Storehouse, services and citizens come in the next
phases. See the roadmap.

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

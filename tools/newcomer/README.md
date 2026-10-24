# Newcomer bot (onboarding test harness)

`NewcomerBot.java` checks Phase 11's "done": a player who has never read the README founds a
town and reaches Worthy Village. It plays a new survival world and does only what the planner's
advisor says.

1. It puts the Town Hall where the founding hint says, on open, fairly flat land: the flattest
   dry spot for the town's first 3×3 tiles within 1,200 blocks of spawn, judged from the world
   generator.
2. It opens the planner.
3. Every five seconds it reads the advisor's first card, worked out on the client exactly as the
   planner draws it. It does what the card's text tells a player to do, in the simplest way.
   - **Stock the storehouse:** craft and place a storehouse, and bring 64 each of dirt,
     cobblestone, logs and planks.
   - **Lay a road:** a lane by the hall.
   - **Zone homes:** the lots beside the road.
   - **Power and water:** a windmill and a well beside a road.
   - **Shops and work:** shops near the hall, industry farther out.
   - **Grow:** zone what the RCIO bars ask for, and lay the next lane when no lot is free.
4. On the last step ("grow") it also works through the cards behind **Next**:
   - **Sites waiting for materials:** gather the material, or buy a stack.
   - **Money:** raise taxes a point.
   - **More utilities:** another mill or well.
   - **Services:** the named service among the homes it misses.
   - Cards with no concrete action (the milestone, the good-town page, unhappy buildings) it
     leaves alone.

It gathers by hand at the autoplayer's pace: 512 blocks to start, then 1,500 per speed-1 hour.
For anything else it buys in with the town's emeralds.

It is not part of the mod. It needs the autoplayer (`tools/autoplayer/AutoPlayer.java`) for the
street grid, site checks and game setup.

## Running it

1. Copy `NewcomerBot.java` and `AutoPlayer.java` into the client package of a test copy of the mod
   (`com.cloudkingtv.townstead.client`). Copy `AdvisorProbe.java` into
   `com.cloudkingtv.townstead.client.planner`: the advisor is package-private, and the probe reads
   its cards.
2. Use a new world (the client run's `--quickPlaySingleplayer`).
3. Create `run/newcomer.flag` and start the client. Set the growth speed with
   `-Dtownstead.autoplay.speed=20`, which is also the default.
4. Optional settings:
   - `-Dtownstead.newcomer.hours=6`: give up after this many speed-1 hours.
   - `-Dtownstead.newcomer.avoid=x,z`: settle at least 500 blocks from a site already tried.
5. Turn off any other scripted tests in the test copy while it runs.

Every card it reads and everything it does goes to `run/newcomer/events.log` (and the game log,
as `[New]`). The run ends with `SUCCESS` at Worthy Village or `FAIL` when time runs out. If a
card leaves it with nothing to do for 60 rounds in a row, it logs an `idle` line.
`run/screenshots/newcomer_*.png` shows the planner each time the first card changes.

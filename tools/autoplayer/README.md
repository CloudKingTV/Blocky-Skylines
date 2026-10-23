# Autoplayer (balance test harness)

`AutoPlayer.java` plays a town from founding the way a person would, through the same calls the
planner's network payloads make, in survival: construction and roads cost materials, and the
mayor gathers a little by hand early on (a starter kit, then up to 1,500 blocks per hour until
Big Town) and otherwise buys in only what the town can't make. Every few seconds it looks at the
town and does what a mayor would: lays the next street when a zone has nowhere to grow, zones by
demand (on lots the game can actually build on), places power and water ahead of need, services
where homes lack them (one a day, only when the books can carry the upkeep), landmarks as they
unlock, buys land, specialises the first industry as forestry and quarries, runs coach lines when
the roads jam, and keeps the books (taxes, a loan in a pinch, trimmed service budgets when broke).

It is not part of the mod. It runs inside a test copy of the mod's client (a client-side
`@EventBusSubscriber`), with the game's growth speed set high so a run is a time-lapse:

1. Copy it into the client package of a test project (`com.cloudkingtv.townstead.client`).
2. Create `run/autoplay.flag` and start `runClient` with these options:
   `-Dtownstead.autoplay.speed=20` (growth speed; 20 means one speed-1 hour every three minutes)
   and `-Dtownstead.autoplay.hours=24` (stop after this many speed-1 hours or at Megalopolis).
3. It writes `run/autoplay/days.csv` (one row per in-game day: people, buildings, sites, money,
   taxes, happiness, demand, traffic, materials, imports) and `run/autoplay/events.log`
   (milestones, roads, zoning, placements, imports, the books, trouble and stalls).

`analyse.py` summarises a run (milestone times, days in debt, how often demand is pinned) and
draws a chart sheet:

    python3 -I analyse.py run/autoplay/days.csv run/autoplay/events.log chart.png

Hours in the output are speed-1 hours (game time at the default growth speed).

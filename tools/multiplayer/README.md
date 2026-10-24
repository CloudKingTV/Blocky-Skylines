# Multiplayer soak (dedicated-server test harness)

Two scripted players on a dedicated server, used for Phase 10. Neither is part of the mod. They
act only through what a real client sends: chat commands, block placement and the planner's
payloads.

- **Alice** takes over a prepared big town whose mayor never comes back, using
  `/townstead transfer Alice <town>`. She makes Bob her co-mayor and runs the town: zoning, roads,
  taxes, budgets, policies, services, imports, and undo/redo. Partway through she lights a fire
  with `/townstead ignite`.
- **Bob** founds a town of his own far away by placing a Town Hall. First he tries what a griefer
  would:
  - edits to Alice's town before he's her co-mayor (bulldoze, tax, rename);
  - a flood of 300 edits in one tick;
  - NaN and infinite road curves;
  - Town Hall and storehouse payloads aimed two million blocks away.

  Each attempt is checked. Then he runs his own town, and once he's co-mayor he takes turns
  editing Alice's.

`SyncProbe` logs one line per town every 100 ticks on the server (`[Sync] S`). The bots log the
same line from their synced copies every 20 ticks (`[Sync] C`). The line covers buildings and
their states, people, construction progress, treasury, taxes, budgets, stock, roads, land,
lines, districts and policies. `sync_check.py` compares the two. A desync is three server
samples in a row (15 s) that a connected client never matched.

`SyncProbe` also checks the town model's indexed lookups against whole-town scans every five
minutes (`[Equiv]`): service reach, homes and workplaces nearby, and counts.

## Setting it up

1. Copy `MultiplayerBot.java` into the client package (`com.cloudkingtv.townstead.client`) and
   `SyncProbe.java` into the root package of a test copy of the mod.
2. Give the test project's `build.gradle` a server run and two client runs, each with its own
   game directory:

   ```groovy
   server {
       server()
       programArgument '--nogui'
       gameDirectory = project.file('run-server')
   }
   alice {
       client()
       gameDirectory = project.file('run-alice')
       programArguments.addAll '--username', 'Alice', '--quickPlayMultiplayer', 'localhost:25565'
       systemProperty 'townstead.bot', 'alice'
       systemProperty 'townstead.bot.ignite', '11000'   // ticks after joining; 12000 by default
   }
   bob {
       client()
       gameDirectory = project.file('run-bob')
       programArguments.addAll '--username', 'Bob', '--quickPlayMultiplayer', 'localhost:25565'
       systemProperty 'townstead.bot', 'bob'
   }
   ```

3. In `run-server/server.properties` set:
   - `online-mode=false` and `enforce-secure-profile=false`;
   - `pause-when-empty-seconds=0`, so the server keeps ticking with nobody on;
   - `enable-rcon=true` and `rcon.password=townstead` (what `rcon.py` uses).

   Make both players operators in `ops.json`, using their offline UUIDs. Put the world to test
   in `run-server/world`. The bots expect a town named "Dev's Town" (`SHARED_TOWN`), such as the
   one the autoplayer builds.
4. Lower `renderDistance` and `maxFps` in each client's `options.txt` (4 and 10 are plenty), and
   set `pauseOnLostFocus:false`.

## Running it

    TOWNSTEAD_TEST=/path/to/test-project SOAK_LOGS=/path/to/logs ./soak.sh

The run takes about 65 minutes:
- It starts the server, then Alice, then Bob.
- When Alice's fire is lit, it restarts the server while the fire burns.
- At 29 minutes it signals Bob (`run-bob/build-now.flag`) to knock down four of his homes, so
  new ones are going up. A minute later it logs him out for four minutes, mid-construction. His
  town is far from Alice's, so its chunks unload.
- Every five minutes it records `/townstead perf` in `soak.log`.

Afterwards, run:

    python3 -I sync_check.py ts-server.log ts-alice.log ts-bob.log

Each bot also writes `bot.log` in its game directory, with its actions and `CHECK ... PASS/FAIL`
lines.

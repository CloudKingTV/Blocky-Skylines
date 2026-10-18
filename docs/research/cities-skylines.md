# Cities: Skylines: Deep Research

Research for **Blocky Skylines**, a Minecraft mod inspired by *Cities: Skylines* (CS1, 2015) and
*Cities: Skylines II* (CS2, 2023). This document describes how those games work. The companion
document [`../design/minecraft-adaptation.md`](../design/minecraft-adaptation.md) works out how
each system could translate to Minecraft.

**How this was researched (2026-10-18).** The usual primary references (the Paradox wikis,
Wikipedia, Steam, Reddit) are blocked from this environment, so the research comes from
web-search summaries of those pages, developer feature posts and diaries, reviews, and community
guides, combined with general knowledge of the games. Where sources disagreed or gave
version-dependent numbers, this document says so instead of picking one. Treat precise figures
as approximate. The mechanics and relationships are the reliable part.

---

## 1. TL;DR: what Cities: Skylines actually is

- A **city-management simulation** where you lay out **roads**, paint **zones** along them, and
  the city **grows itself**. Buildings appear on zoned land when there is **demand**, then **level
  up** as you supply services, education and land value.
- Your job is to keep a **web of interlocking needs** satisfied: utilities (power, water,
  sewage), services (garbage, health, deathcare, fire, police, education, parks), **jobs vs
  workers**, **goods flow**, **traffic**, **pollution**, and **money**.
- **Traffic is the signature challenge.** Every trip is simulated by agents on a lane-level road
  network, so road design is the main puzzle and the most talked-about part of the game.
- **Progression** comes from population **milestones** that unlock buildings, policies, districts,
  more map tiles and eventually **unique buildings and monuments**.
- **Feedback** is visual and constant: colored **info views** (heat maps), building status
  icons, demand bars, a city budget, and **Chirper**, a fake social feed of citizen complaints.
- CS1 succeeded as a **big, free sandbox with deep modding**, launched right after SimCity
  (2013) disappointed players. CS2 (2023) added a deeper economy and more systems, but launched
  with severe **performance** and **economy-simulation** problems.

---

## 2. Franchise facts

| | Cities: Skylines (CS1) | Cities: Skylines II (CS2) |
|---|---|---|
| Release | March 2015 | October 2023 |
| Developer / publisher | Colossal Order (Finland) / Paradox Interactive | Same |
| Engine | Unity (Mono, C#), heavy single-thread simulation | Unity DOTS/ECS (multi-threaded simulation) |
| Map | 5×5 grid of tiles, each **1.92 km × 1.92 km**. **9 tiles** buildable in vanilla (≈33 km²); mods unlock all 25 or 81 | **441 tiles** (21×21) totaling **≈159 km²**. Tiles are roughly ⅓ the size of CS1's |
| Zoning grid | **8 m × 8 m cells** | **8 m × 8 m cells** (same unit) |
| Modding | Steam Workshop, C# mods, asset and map editors. A huge ecosystem (TM:PE, Move It, Network Extensions, Realistic Population…) | Paradox Mods. Editor and asset tools arrived after launch |
| Reception | Very positive. Became the genre leader | "Mostly Negative" on Steam for months: performance, broken economy, traffic AI quirks. Improved through patches ("Economy 2.0") |

CS1 DLC expansions, roughly in order: After Dark (2015), Snowfall, Natural Disasters, Mass
Transit, Green Cities, Parklife, Industries, Campus, Sunset Harbor (2020), Airports, Plazas &
Promenades, Financial Districts, Hotels & Retreats. Plus many content-creator asset packs and
radio stations.

---

## 3. The core loop

```
          ┌─────────────── money (taxes) ─────────────────┐
          ▼                                               │
  build ROADS ──► paint ZONES ──► DEMAND spawns buildings ┤
          ▲                         │                     │
          │                  buildings need:              │
          │      utilities (power/water/sewage)           │
          │      services (garbage, health, fire, police, │
          │                education, deathcare, parks)   │
          │      access (roads → traffic)                 │
          │                         ▼                     │
          │            citizens move in, work, shop,      │
          │            get educated, age, die             │
          │                         ▼                     │
          │      satisfied needs → HAPPINESS & LAND VALUE │
          │                         ▼                     │
          │      buildings LEVEL UP → more people, more   │
          │      taxes, more demand, more traffic         │
          └──── milestones unlock new tools & land ◄──────┘
```

The game's tension comes from coupling. Every fix creates a new load: more industry means more
jobs but also more pollution, cargo trucks and traffic. Higher density means more taxes but also
more garbage, more dead to collect and more sewage.

---

## 4. Systems deep dive

The original game is the baseline. CS2 differences are noted with **CS2:** markers.

### 4.1 Map, terrain and natural resources

- **Tiles.** You start with one tile and buy neighbors as milestones grant permits/area unlocks,
  paying cash per tile. In CS1, about 9 of 25 tiles are purchasable in vanilla; Worthy Village gives the
  2nd tile, Boom Town the 3rd, and the full 9 come with Megalopolis.
  **CS2:** 441 small tiles bought with expansion permits from milestones.
- **Outside connections.** Highways, railways, shipping lanes and air routes at the map edge.
  These carry new residents and tourists in, and are how goods are **imported and exported**.
- **Natural resources** (CS1: four types, colored overlay): **forest** (green, renewable),
  **fertile land** (yellow, renewable), **ore** (blue, non-renewable), **oil** (black,
  non-renewable). Exploited by painting a district with an **industry specialization** over
  them. Approximate trade-offs from guides: oil ≈ +35% tax and +30% pollution vs generic
  industry; ore ≈ +20% and +20%; forest and farming ≈ +10% each, with farming using more water
  and forest adding noise. **CS2** adds **groundwater** (for geothermal power and wells).
- **Terrain.** There's a landscaping tool (raise/lower/level, costs money) and a real **water
  simulation**: rivers flow, dams hold back water and can fail, and floods are possible. Water
  sources and drains are placed in the map editor. Reviewers noted terrain is fairly static
  during play compared with SimCity-style terraforming.

### 4.2 Roads and the traffic simulation

**Roads are the skeleton of everything.** Zoning appears along roads, all utilities and
services reach buildings via roads, and every agent trip uses the road graph.

- **Road types (CS1):** small (2-lane, zonable), medium (4-lane avenue), large (6-lane),
  one-way variants, highways and ramps (not zonable), gravel roads, plus decorative variants
  (trees, grass, bus lanes, bike lanes from After Dark, tram tracks from Snowfall). Elevated
  roads, bridges and tunnels.
- **Tools:** straight, curved, freeform and **upgrade** (swap a road type in place, or flip a
  one-way). Snapping and guidelines help with grids.
- **Road hierarchy** (the player-taught discipline): **highways → arterials (large roads) →
  collectors (medium) → local streets (small)**. Good cities keep through-traffic off local
  streets and avoid direct highway-to-local connections.
- **Intersections:** traffic lights and stop logic are automatic in vanilla. Roundabouts and
  interchanges are player-built from road pieces. Players learn to match lane counts in and out
  of junctions and to space intersections apart.
- **Traffic AI:** every vehicle is an agent with a path from origin to destination. Pathfinding
  favors faster or shorter routes; vehicles commit to lanes early, which causes the infamous
  "everyone queues in one lane" problem. Service vehicles (garbage trucks, hearses, ambulances,
  fire trucks, police) and **cargo trucks** share roads with cars, so a jam can stop services
  and cause garbage piles and uncollected dead to cascade. A **despawn** option (vehicles stuck
  too long vanish) hides jams without fixing them.
  **CS2:** vehicles spread across lanes, re-evaluate mid-trip, switch lanes to optimize flow,
  model roundabout yielding, simulate **parking** (cars must find spots; parking fees) and have
  **accidents**.
- **What players added through mods: Traffic Manager: President Edition (TM:PE)**, the
  most-installed CS1 mod. It adds lane arrows, a lane connector, priority signs
  (priority/yield/stop), timed traffic lights, junction restrictions (U-turns, lane changes,
  entering blocked junctions), per-segment speed limits, vehicle-type restrictions and parking
  restrictions. This is direct evidence that **players want fine-grained control over
  intersections**, and CS2 brought some of it into the base game.

### 4.3 Zoning and building growth

- **Zone types:** **Residential** (low/high density), **Commercial** (low/high), **Industrial**
  (generic plus specializations), **Office**. CS1 uses a dual density split for R and C.
  **CS2** adds medium density, **row housing**, **mixed-use** (shops below, flats above),
  **low-rent** housing and office variants.
- **The zoning grid** appears automatically along zonable roads, up to **4 cells deep** on each
  side in CS1 (the classic grid of 8-cell blocks between roads), with **8 m cells**. **CS2**
  allows deeper zoning (reported up to 6 cells). Tools: fill, paint, marquee and erase (dezone).
- **Growables:** buildings are not placed by the player. They **spawn on zoned cells** when the
  zone type has **demand**, the cells have road access, and (for most types) power and water
  connections. Footprints run from 1×1 up to 4×4 cells, and corner lots get special models.
  Buildings are chosen at random from a pool per zone type, density and level, and can be
  themed by district style.
- **Building levels.** **Residential has 5 levels; commercial, industrial and office have 3**
  (**CS2:** all zoned buildings have levels 1–5). Leveling raises taxes, capacity and visual
  quality. A building's info panel shows a **level-up progress bar** with the missing factors.
  Requirements by type:
  - *Residential:* the **education** of residents (top levels need university-educated people)
    plus **land value** and service coverage (health and safety matter).
  - *Commercial:* **land value** and **wealthy customers** (the levels of nearby residential).
    Sources disagree on how much workforce education matters.
  - *Industrial:* **educated workers** and **services** (health, deathcare). **Land value is
    not a factor.**
  - *Office:* **highly educated workers** and services, plus land value at the top level.
  - **CS2:** levels are tied to building *condition*: renters pay upkeep, and wealthier tenants
    lift the level. Levels 3 and 5 change the model and add apartments.
- **Abandonment / decline:** buildings become **abandoned** when unhappy for too long. Causes
  include no power/water, uncollected garbage, uncollected dead, pollution sickness, no
  customers/workers, high taxes or crime. **Burned** and **collapsed** states come from fires and
  disasters. Abandoned buildings pay no tax and lower nearby land value. Bulldoze them to rebuild.
- **Historical buildings** (CS1 patch): lock a building's level and appearance, for players
  who like a particular look.
- **CS2 signature buildings:** unique zoned "landmark" buildings unlocked through progression,
  placed manually on zoned land, with area bonuses.

### 4.4 Demand (the RCI(O) bars)

- The UI shows **demand bars** for residential, commercial and workplace (industry/office). CS1
  folds industry and office into one "workplace" bar; CS2 splits them. They show *how much new
  construction of each type would be filled*, not current health.
- **Residential** demand rises when there are **open jobs** (workers wanted) and falls when
  unemployment rises. **Commercial** demand comes from residents' **consumer needs** and the
  goods supply. **Industrial** demand comes from commercial goods needs plus **exports**.
  **Office** demand comes from the **highly educated workforce**.
- Practical rules players learn: watch **unemployment** (around 5% is healthy; above about 10%,
  zone jobs). **Over-zoning** (lots of empty zoned land) suppresses demand. Demand is the
  main pacing mechanic, because the city can't grow faster than it creates reasons to live
  there.
- **CS2:** demand is driven by a fuller economy: household money, rent vs income, company
  profits and resource availability. Pre-Economy-2.0 versions had well-publicized bugs here.

### 4.5 Citizens ("cims")

- **Life stages (CS1):** child → teen → young adult → adult → senior → death. Lifespans are
  compressed (about 6 in-game years). Births and deaths cluster, causing **death waves** a
  generation after a big move-in, which can overwhelm deathcare (a classic emergent problem).
  **CS2:** children, teens, adults, seniors. Seniors slowly lose health, can't work or study,
  and die.
- **Education (CS1, 4 tiers):** uneducated → educated (elementary) → well educated (high
  school) → highly educated (university). Each school serves an age band, and capacity and
  coverage limit throughput. **CS2:** 5 tiers (adds "poorly educated"); teens attend high
  school, adults college or university; jobs have per-education slots.
- **Households** live in residential buildings. Members have jobs (matched by education,
  with over-educated people taking lower jobs), commute, shop, use services and go to leisure.
- **Happiness** (CS1): driven mainly by **service coverage** plus pollution, noise, crime,
  taxes, employment and entertainment. Happy citizens make leveling more likely and abandonment
  less likely. **CS2** splits it into **well-being** (utilities, garbage, pollution, crime,
  services) and **health** (healthcare access, pollution, age). Happiness also affects company
  efficiency.
- **Tourists** are temporary citizens arriving through outside connections. They visit unique
  buildings, parks and leisure districts, and pay fares and spending.
- **Agent limits (CS1, community-documented):** about **1,048,576 citizens** in data, but only
  **65,536 citizen instances** (walking, physically present cims), **16,384 active vehicles**
  and **32,768 parked**, **49,152 buildings**, **36,864 road segments**, **32,768 nodes**. In
  other words, **most of the population is just data rows**, and only a capped subset is
  embodied on screen at once. That abstraction is what makes large cities possible, and it's
  the key architectural lesson for a Minecraft adaptation.

### 4.6 Utilities

- **Electricity (CS1):** power plants feed the grid through **power lines**, and buildings
  conduct power to adjacent buildings, so a contiguous zoned area forms one grid. Plants range
  from cheap and dirty to clean and expensive: **coal** and **oil** (pollution), **wind
  turbine** and advanced wind (noise; output depends on a wind map), **solar**, **hydroelectric
  dam** (needs flowing water), **nuclear**, the **Fusion** monument, and **geothermal** (CS2).
  Shortages turn buildings off, then they become unhappy, then abandoned.
  **CS2:** low-voltage power runs along roads; high-voltage lines need transformers; power is
  traded with outside connections.
- **Water & sewage (CS1):** **water pumping stations** sit on shores and take whatever
  pollution is in the water; **water towers** draw groundwater; **pipes** (underground) deliver
  water, with buildings within about 11 cells of a pipe served; **sewage outlets** dump into
  water bodies and pollute downstream; **water treatment plants** reduce that pollution. The
  classic mistake is putting the intake downstream of the outlet, which poisons the city and
  causes a sickness and death spiral. DLC added eco treatment, inland treatment and similar.
  **CS2:** pipes go along roads; output is measured (a basic pump ≈100,000 units, an advanced
  one up to ≈1,000,000 from surface water).
- **Heating** (Snowfall DLC): boiler stations and geothermal heating in winter maps.

### 4.7 City services

Most services work the same way: a **building with a coverage radius** and/or a **fleet of
vehicles** that physically drive to buildings. Budget sliders scale effectiveness, and
capacity limits matter.

| Service | Buildings (CS1 base unless noted) | Mechanic |
|---|---|---|
| **Garbage** | Landfill (fills up, can be emptied), Incinerator (burns, pollutes), Recycling Center (Green Cities), Waste Processing Complex/transfer (Sunset Harbor) | Buildings generate garbage, trucks collect it. Piles cause unhappiness and abandonment. Landfills are storage, not disposal |
| **Deathcare** | Cemetery (cheap, about 10 hearses, fills up, can be emptied), Crematorium (expensive, about 7 hearses, unlimited) | Hearses collect the dead. Uncollected dead cause unhappiness and abandonment. Death waves stress capacity |
| **Healthcare** | Medical clinic, Hospital, Medical Laboratory (unique), DLC: eldercare, childcare, helicopter depots, saunas | Coverage gives a health bonus. Ambulances carry the sick. Pollution and bad water cause sickness |
| **Fire** | Fire house, Fire station, helicopters (DLC) | Fire trucks respond. Fire spread (Natural Disasters). Burned buildings must be rebuilt |
| **Police** | Police station, HQ, Prison (After Dark), helicopters | Coverage lowers crime. Crime lowers land value and happiness |
| **Education** | Elementary, High school, University. Campus DLC areas (university, trade school, liberal arts) with levels, varsity sports and academic works. Libraries (Campus) | Capacity × coverage drives education levels, which drive leveling and office demand |
| **Parks & Plazas** | Parks, plazas, playgrounds, sports, Parklife **park areas** (city park, amusement park, zoo, nature reserve) with levels | Raise land value and entertainment/happiness. Attract tourists. Park areas earn ticket income |
| **Unique buildings** | Dozens (Stadium, Opera House, Expo Center, Grand Mall, Observatory, etc.) | Big happiness, land value and tourism boosts. Each has an **unlock condition** |
| **Monuments** | Eden Project, Hadron Collider, Medical Center, Space Elevator, Fusion Power Plant (base game; DLCs add more) | Endgame. Require the last milestone, all tiles and specific unique buildings |
| **Post** (Industries) | Post office, sorting facility | Mail collection and delivery is a happiness factor |
| **Communications** (CS2) | Telecom, internet coverage | Coverage feeds well-being and offices |
| **Maintenance** | Road maintenance depot; snow plowing (Snowfall) | Road condition and speed |
| **Disaster response** (ND) | Shelters, emergency response, early warning, detection | Evacuation and recovery |

Budget sliders: running services at 50–150% budget changes effectiveness (vehicle count,
coverage radius, capacity). Guides report non-linear effects, with 50% budget giving well
under half the effect.

### 4.8 Public transport & tourism

- **Lines:** you place a **depot** (spawns vehicles) and draw a **line** by clicking stops in
  order (it loops). Modes (base + DLC): **bus**, **metro**, **train** (passenger and cargo),
  **ship/ferry**, **airplane/airport**, **taxi** (After Dark), **tram** (Snowfall), **monorail**,
  **cable car**, **blimp** (Mass Transit), **trolleybus**, **intercity bus** (Sunset Harbor),
  sightseeing tours, and helicopters (DLC).
- **Line management:** vehicle count (via budget, later per-line), ticket price per line type,
  ridership stats, colors and names. Transit hubs connect modes.
- **Economics:** transit generally **loses money**, with depots and vehicles as the main costs.
  Its real value is traffic reduction, land value and access. Community-reported default fares
  and capacities: bus about 30 riders, tram about 90, metro about 180, train about 240.
- **Tourism:** tourists arrive by outside connections, ride transit, visit attractions, stay in
  hotels (Hotels & Retreats) and spend money. Leisure and tourism commercial specializations
  (After Dark) change the commercial mix. The day/night cycle (After Dark) shifts activity:
  leisure areas are busiest at night.

### 4.9 Pollution, noise, land value, crime

- **Ground pollution:** from generic industry, garbage facilities, coal and oil plants. It
  spreads in a radius, makes residents **sick** (health falls, deaths rise), lowers land value
  and **can't be fixed by the building that caused it**. Remove the source and wait.
- **Water pollution:** from sewage outlets (and industry DLC). It travels with the water
  simulation. It's only dangerous if your **intake** draws it, but then it affects the whole
  city.
- **Noise:** from traffic, commercial (especially high density), wind turbines, airports, rail
  and forestry/farming. It's localized and clears quickly when the source goes. Mitigations:
  trees, tree-lined roads, sound barriers, and **offices as buffers** (offices make no noise or
  ground pollution).
- **Land value:** the composite desirability of each spot. It's raised by **parks** (the single
  biggest lever, especially leisure access), service coverage (education, health, police, fire,
  deathcare), transit access and unique buildings, and lowered by pollution, noise, crime,
  garbage and abandonment. It's **required for top levels of residential, commercial and office**
  but **irrelevant for industry**. **CS2:** land value feeds **rent**; one patch-note formula is
  `Rent = (LandValue + ZoneType × BuildingLevel) × LotSize × SpaceMultiplier`.
- **Crime:** it grows with population, unemployment and low education, and is reduced by police
  coverage. It lowers happiness and land value. Prisons (After Dark) hold criminals.

### 4.10 Economy

- **Income:** **taxes** per zone (and per density in CS1). Residential taxes come from wages,
  commercial, industrial and office from company profits. Plus transit fares, tourism, park
  tickets, industry-area profits (Industries DLC: you *own* those buildings, so you earn
  profits instead of taxes and absorb losses) and service fees (CS2).
- **Tax rates:** sliders from roughly **−10% to +30%**, with defaults around 9–12% considered
  safe. High taxes reduce demand and happiness. Low taxes boost growth.
- **Expenses:** **upkeep** of every service building, road, depot and vehicle (scaled by the
  budget slider), loan payments, and policy costs.
- **Loans:** a few loan slots with interest. Guides treat them as a last resort.
- **CS2:** a fuller economy. Households have money and pay **rent** and buy goods; **companies**
  buy input resources and produce outputs (real production chains, imports and exports); taxes
  can be set **per education level** (residential) and **per product** (business); the city
  sets **service fees** (electricity, water, healthcare, garbage, parking). "Economy 2.0" removed
  the virtual landlord and city subsidies to make money flow transparent. **Lesson: a deep
  economy that players can't see or reason about feels broken even when it's working.**

### 4.11 Districts, policies, and special areas

- **Districts:** paint an area with a brush and name it (the name appears on the map). Districts
  are the unit for **policies** and **specializations**.
- **Policies** (city-wide or per district) are switches with costs and effects. Examples:
  smoke detectors (fire), free public transport, recycling, high-rise ban, heavy-traffic ban,
  small-business enthusiast, big-business benefactor, education boost, encourage biking, pet
  ban, power/water saving, parks & recreation (land value), high-tech housing, and tax breaks.
  Categories: industry specialization, services, taxation, city planning.
- **Specializations:** industry (forest, farming, ore, oil; later the Industries DLC areas),
  commercial (tourism, leisure, organic & local produce, wall-to-wall), office (IT cluster,
  Green Cities), residential (self-sufficient, wall-to-wall).
- **Special "area" types** (DLC), each drawn like a district, anchored by a main building, and
  **leveled up** by meeting goals:
  - **Park areas** (Parklife): entrance gate determines type; paths and attractions raise
    entertainment; levels 1–5.
  - **Industry areas** (Industries): a full supply chain (§4.12).
  - **Campus areas** (Campus): universities with levels, academic works and varsity sports.
  - **Pedestrian areas** (Plazas & Promenades): car-free zones with 20 mph limits inside, plus
    **service points** that handle cargo and garbage for the whole area.
  - **Airport areas** (Airports): build-your-own terminals, gates and runways.
  - **Financial districts** (Financial Districts): banks and a stock exchange with an
    **investment** system (13 categories).

### 4.12 Production chains (Industries DLC / CS2)

- **Chain:** extractor (farm, forest, ore mine, oil pump) → storage → **processor** (raw to
  processed goods) → storage → **unique factory** (processed to **luxury goods**; 16 of them,
  some need inputs from several industries, e.g. the toy factory needs timber, paper and
  plastic) → storage → commercial or export.
- **Warehouses** buffer and route goods (fill, empty or balanced modes). Cargo trucks, trains,
  ships and planes move them. Industry areas level up with production volume and worker counts.
- **The fun part** is logistics balancing: matching extraction to processing, routing trucks
  and avoiding a factory that starves for one input.
- **CS2** generalizes this: every company is a node in a resource economy.

### 4.13 Progression

- **CS1 milestones:** 13 population milestones (Little Hamlet → … → Megalopolis). Thresholds
  vary by map and version, and sources disagree (one list runs 500 → 80,000). Each gives cash
  and unlocks. Roughly:
  - Little Hamlet: elementary school, medical clinic, landfill, taxes and loans.
  - Worthy Village: police, fire, **districts & policies**, 2nd map tile.
  - Tiny Town: high school, parks.
  - Boom Town: cemetery, transit begins (bus), 3rd tile.
  - Busy Town onward: transit modes (metro, train), harbor, airport, university, nuclear,
    more tiles.
  - Megalopolis: all 9 tiles; monuments become possible.
- **Unique buildings** each have a **condition** ("X squares of industrial zone", "N tourists",
  "1,000 abandoned buildings for 5 weeks" for the Observatory, …). These become a
  **challenge/achievement layer**. **Monuments** require specific unique buildings plus all
  tiles plus the final milestone.
- **CS2:** **Expansion Points (XP)**, earned *passively* (16 times a day from population and
  happiness) and *actively* (placing or upgrading services, signature buildings, road
  expansion). 20 milestones (Tiny Village → Megapolis) give cash, **development points**
  (spent in per-service **development trees**; ≈232 total, ≈228 to buy everything) and
  **expansion permits** for tiles. **Signature buildings** are the landmark rewards.

### 4.14 Disasters, time, weather

- **Natural Disasters DLC:** 7 types (earthquake, thunderstorm, tsunami, forest fire, meteor,
  tornado, sinkhole), toggleable. 16 related buildings (shelters, early warning, emergency
  response, disaster detection). Fire spread. Destruction and rebuild loop. A divisive DLC that
  fits some players' style and not others.
- **Day/night** (After Dark): visual lighting plus time-of-day behavior (commuting, nightlife).
  **Seasons and weather** (CS2): temperature, snow and rain affecting heating, snow plowing and
  tourism.

### 4.15 Information & feedback (this is the real UI)

- **Info views** (heat-map overlays that recolor the city): electricity, water/sewage,
  garbage, health, happiness, population, noise, ground/water pollution, transport, natural
  resources, land value, districts, education, crime, fire safety, entertainment, tourism,
  wind, traffic (congestion coloring), outside connections, level (progress), leisure,
  heating, escape routes, radio and destruction (DLC), traffic routes (who goes where).
- **Building status icons** above buildings: no power, no water, garbage, dead, sick, no
  workers, no customers, not enough goods, abandoned, on fire, etc. **Notification icons are
  the main way problems surface.**
- **Info panels:** clicking any building shows residents/workers, education, level progress
  with missing factors, and the vehicles en route. Clicking any vehicle or cim shows origin,
  destination and the route.
- **Statistics:** population, demographics and education charts, budget and income, the
  traffic flow percentage.
- **Chirper:** a Twitter-parody feed of citizen posts, both complaints ("No water!", "The
  traffic is terrible") and celebrations (milestones). It's humorous, but it acts as a **prioritized
  alert channel**. It was so ubiquitous that "turn off Chirper" became a popular mod. CS2 kept
  it, with citizen sentiment and tie-ins to happiness.
- **Demand bars** and the **traffic flow %** are the two always-visible city health
  indicators.

### 4.16 Controls & UX

- **Free RTS camera:** pan, rotate, zoom from street level to the whole map, tilt. Cinematic
  and free-camera modes for screenshots.
- **Bottom toolbar** organized by category (Roads, Zoning, Districts, Electricity, Water,
  Garbage, Health, Fire, Police, Education, Transport, Parks, Unique, Monuments, …) with
  sub-tabs and thumbnails. Tool modes show **coverage radius and costs before placement**.
- **Undo** is limited (CS1 had none for most actions; mods like Move It added it). **Bulldoze**
  is a tool, with refunds for recently built items.
- **Simulation speed** (pause/1×/2×/3×), and **unlimited money** / **unlock all** as
  sandbox settings at game start.

### 4.17 Modding ecosystem (what the community kept adding)

The most popular CS1 mods show what the base game was missing:

- **TM:PE**: intersection and traffic control (§4.2).
- **Move It**: select, move, rotate and copy anything; precision and undo.
- **Network Extensions / Node Controller / Intersection Marking Tool**: more road types,
  custom intersections, painted road markings.
- **81 Tiles**: build on the whole map.
- **Realistic Population**: realistic household and worker counts per building.
- **Fine Road Tool / Precision Engineering / Road Anarchy**: exact placement and freedom.
- **Find It / Ploppable RICO**: asset browsing; placing growable buildings manually.
- **Hide It / Theme Mixer / Lighting mods**: visuals.
- **Asset Workshop**: thousands of buildings, props and vehicles, which is a big reason CS1
  lasted for years.

---

## 5. What made it great and what went wrong

**CS1's winning formula**
1. **Generous sandbox**: big land, few hard constraints, you play your way (grid planner,
   organic town, traffic engineer, detail decorator).
2. **Roads as a toy**: building interchanges is satisfying on its own.
3. **Legible cause and effect**: every problem shows up as an icon, a heat map or a Chirper post,
   and fixes are obvious and spatial ("place a clinic here"). The player is a **doctor
   diagnosing the city**.
4. **Emergence**: death waves, traffic cascading into service failure, the
   pollution-and-sickness spiral. Problems arise from rules rather than scripts.
5. **Moddability**: the community extended content and fixed gaps for a decade.
6. **Timing**: arrived when SimCity (2013) disappointed (tiny maps, always-online).

**CS1's common complaints**
- Traffic AI quirks (lane commitment, roundabout behavior), plus a need for mods for basic
  control.
- No base-game undo or precise placement.
- Shallow economy (money becomes trivial mid-game).
- Repetitive building models without assets.
- No multiplayer.

**CS2's lessons (from launch reviews)**
- **Performance**: heavy defaults and expensive rendering made it unplayable on many PCs.
- **An economy you can't read**: a complex, buggy simulation (rent, households) produced
  "high rent killing the city" outcomes players couldn't diagnose. Colossal Order admitted it
  wasn't up to standard and shipped "Economy 2.0".
- **Overpromised depth**: zoning control and transit options less than advertised.
- **The positive side:** upgradeable services, district-assigned services, better traffic
  lane use, parking, mixed-use zoning, and the XP/development-tree progression were all
  well-received ideas.

**Design principles to carry forward**
1. Make every system **visible** (overlay + icon + panel), or players won't trust it.
2. Prefer **few, legible rules with emergent interactions** over a deep, opaque economy.
3. **Abstract the population**; embody a sample. Performance is a feature.
4. Give the player **precise tools** (snapping, undo, copy) from day one.
5. **Progression unlocks *tools and land***, not just numbers.
6. Leave room for **content packs and modding** (data-driven buildings and policies).

---

## 6. Glossary

- **Growable**: a building the simulation spawns on zoned land. **Ploppable**: a building the
  player places (services, unique buildings).
- **Cim**: a citizen. **Citizen unit**: a household/workplace data slot.
- **Info view**: a heat-map overlay mode.
- **u**: road width unit = 8 m = 1 zone cell.
- **RCI(O)**: residential/commercial/industrial(/office) demand.
- **Death wave**: a synchronized die-off one generation after a population boom.

---

## Sources

Developer and official:
- [Cities: Skylines II Feature Highlight #2: Traffic AI](https://www.paradoxinteractive.com/games/cities-skylines-ii/features/traffic-ai)
- [CS2 Feature Highlight #4: Zones & Signature Buildings](https://www.paradoxinteractive.com/games/cities-skylines-ii/features/zones-signature-buildings)
- [CS2 Feature Highlight #5: City Services](https://www.paradoxinteractive.com/games/cities-skylines-ii/features/city-services-districts-policies)
- [CS2 Feature Highlight #7: Maps & Themes](https://www.paradoxinteractive.com/games/cities-skylines-ii/features/maps-themes)
- [CS2 Feature Highlight #9: Economy & Production](https://www.paradoxinteractive.com/games/cities-skylines-ii/features/economy-production)
- [CS2 Feature Highlight #10: Game Progression](https://www.paradoxinteractive.com/games/cities-skylines-ii/features/game-progression) · [Dev Diary #10](https://colossalorder.fi/news/development-diary-10-game-progression/)
- [CS2 Feature Highlight #11: Citizen Simulation & Lifepath](https://www.paradoxinteractive.com/games/cities-skylines-ii/features/citizen-simulation-lifepath) · [Dev Diary #11](https://colossalorder.fi/?p=1851)
- [CS2 Economy 2.0 dev diary](https://www.paradoxinteractive.com/games/cities-skylines-ii/news/dev-diary-economy-part-one)
- [CS1 Dev Diary 3: Natural resources](https://forum.paradoxplaza.com/forum/threads/cities-skylines-dev-diary-3-natural-resources.808920/) · [Dev Diary 8: Districts and policies](https://forum.paradoxplaza.com/forum/threads/cities-skylines-dev-diary-8-districts-and-policies.831059/)
- [CS1 User Manual (PDF)](http://cdn.akamai.steamstatic.com/steam/apps/255710/manuals/CitiesSkylines-UserManual_EN.pdf)
- [TM:PE wiki: Priority Signs](https://github.com/CitiesSkylinesMods/TMPE/wiki/Priority-Signs)

Wikis and references:
- [CS1 Wiki: Zoning](https://skylines.paradoxwikis.com/index.php?title=Zoning&mobileaction=toggle_view_desktop) · [Happiness](https://skylines.paradoxwikis.com/Happiness) · [Land value](https://skylines.paradoxwikis.com/index.php?mobileaction=toggle_view_mobile&section=1&title=Land_value) · [Water and sewage](https://skylines.paradoxwikis.com/Water_and_sewage) · [Monuments](https://skylines.paradoxwikis.com/Monuments) · [Info views](https://skylines.paradoxwikis.com/Info_views) · [Industries](https://skylines.paradoxwikis.com/Industries) · [Supply chain](https://skylines.paradoxwikis.com/Supply_chain) · [Park Areas](https://skylines.paradoxwikis.com/Park_Areas) · [Plazas and Promenades](https://skylines.paradoxwikis.com/Plazas_and_Promenades) · [Financial Districts](https://skylines.paradoxwikis.com/Financial_Districts) · [Commercial specialization](https://skylines.paradoxwikis.com/Commercial_specialization) · [Policies](https://skylines.paradoxwikis.com/index.php?section=11&title=Policies) · [Citizens](https://skylines.paradoxwikis.com/index.php?title=Citizens&section=6) · [Downloadable content](https://skylines.paradoxwikis.com/Downloadable_content)
- [Fandom: Milestone](https://skylines.fandom.com/wiki/Milestone) · [District](https://skylines.fandom.com/wiki/District) · [Policy](https://skylines.fandom.com/wiki/Policy) · [Pollution](https://skylines.fandom.com/wiki/Pollution) · [Noise pollution](https://skylines.fandom.com/wiki/Noise_pollution) · [Zoning](https://skylines.fandom.com/wiki/Zoning)
- [CS2 Wiki: Zoning](https://cs2.paradoxwikis.com/Zoning) · [Citizens](https://cs2.paradoxwikis.com/Citizens) · [Maps](https://cs2.paradoxwikis.com/Maps) · [Natural resources](https://cs2.paradoxwikis.com/Natural_resources) · [Service buildings](https://cs2.paradoxwikis.com/Service_buildings) · [Transportation](https://cs2.paradoxwikis.com/Transportation) · [Economy](https://cs2.paradoxwikis.com/index.php?title=Economy&veaction=edit&mobileaction=toggle_view_desktop)
- [Wikipedia: Cities: Skylines](https://en.wikipedia.org/wiki/Cities:_Skylines) · [Cities: Skylines II](https://en.wikipedia.org/wiki/Cities:_Skylines_II)

Journalism, reviews and analysis:
- [Game Developer: how roads became the backbone of CS2](https://www.gamedeveloper.com/design/how-colossal-order-turned-roads-into-the-backbone-of-cities-skylines-ii)
- [PCGamesN: CS2 economy not up to standard](https://www.pcgamesn.com/cities-skylines-2/broken-economy) · [Economy 2.0](https://www.pcgamesn.com/cities-skylines-2/update-economy-2-0) · [Map size typo](https://www.pcgamesn.com/cities-skylines-2/map-size) · [DLC guide](https://www.pcgamesn.com/cities-skylines/dlc-guide-best)
- [Gameranx: CS2 economy overhaul patch](https://gameranx.com/updates/id/502137/article/cities-skylines-2-overhauls-economy-with-massive-new-patch/)
- [Forbes: CS2 one year on](https://www.forbes.com/sites/barrycollins/2024/09/15/cities-skylines-ii-one-year-on-is-it-worth-playing-now/)
- [Why CS2 performs poorly (paavohtl's blog)](https://blog.paavo.me/cities-skylines-2-performance/)
- [Medium: How CS2 derailed on launch](https://medium.com/@reubenfink/off-the-rails-how-cities-skylines-ii-derailed-on-launch-b474e035f4fb)
- [PC Gamer: CS2 map size](https://pcgamer.com/cities-skylines-2-map-size) · [CS1 DLC worth buying](https://www.pcgamer.com/cities-skylines-has-over-dollar250-of-dlcwhats-worth-buying/)
- [GameSpot: Natural Disasters review](https://www.gamespot.com/reviews/cities-skylines-natural-disasters-review/1900-6416585/)
- [GameWatcher review](https://www.gamewatcher.com/reviews/cities-skylines-review/12175) · [G2A: SimCity vs Cities Skylines](https://www.g2a.com/news/features/simcity-vs-city-skylines/)
- [Love Cities Skylines: Industries review](https://www.lovecitiesskylines.com/industries-expansion-review/) · [After Dark review](https://www.lovecitiesskylines.com/after-dark-review-tourism-expansion/)
- [Screen Rant: biggest CS2 differences](https://screenrant.com/cities-skylines-2-gameplay-differences-first-game-comparison/)

Community guides (numbers community-reported):
- [Steam guide: Cities Skylines Game Limits](https://steamcommunity.com/sharedfiles/filedetails/?id=2712549268)
- [Steam guide: How residential zones work](https://steamcommunity.com/sharedfiles/filedetails/?id=406376334)
- [Steam guide: Transportation Economics](https://steamcommunity.com/sharedfiles/filedetails/?id=1969237818)
- [Steam guide: Explained: Vanilla and DLC Industry](https://steamcommunity.com/sharedfiles/filedetails/?id=2277105979)
- [Game Rant: handling the dead](https://gamerant.com/cities-skylines-how-to-handle-the-dead/) · [Water and sewage tips](https://gamerant.com/cities-skylines-water-and-sewage-tips/) · [Unique buildings and monuments](https://gamerant.com/cities-skylines-unique-buildings-and-monuments/) · [Upgrade buildings](https://gamerant.com/cities-skylines-how-upgrade-buildings/)
- [Dexerto: CS2 development trees](https://www.dexerto.com/gaming/cities-skylines-2-development-trees-unlockables-and-costs-2349659/)
- [Guide Strats: skyscrapers / leveling](https://guidestrats.com/cities-skylines-how-to-get-skyscrapers/) · [Fusion Power Plant](https://guidestrats.com/cities-skylines-fusion-power-plant/) · [Sick citizens](https://guidestrats.com/cities-skylines-fix-sick-citizens/)
- [gameplay.tips: optimal square grid](https://gameplay.tips/guides/7181-cities-skylines.html)
- [gamepressure: building levels](https://guides.gamepressure.com/citiesskylines/guide.asp?ID=30094) · [budget, policies](https://guides.gamepressure.com/citiesskylines/guide.asp?ID=30100) · [information panel](https://guides.gamepressure.com/citiesskylines/guide.asp?ID=30101)

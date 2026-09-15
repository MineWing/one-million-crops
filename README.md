<h1 align="center">OneMillionCrops</h1>

<p align="center">
  <strong>One million of every crop. One shared challenge. Every harvest counts.</strong>
</p>

<p align="center">
  <img alt="Paper 1.21.11" src="https://img.shields.io/badge/Paper-1.21.11-2E8B57?style=for-the-badge">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-E76F00?style=for-the-badge">
  <a href="https://github.com/MineWing/one-million-crops/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/MineWing/one-million-crops?style=for-the-badge&label=Release"></a>
  <a href="https://github.com/MineWing"><img alt="MineWing" src="https://img.shields.io/badge/MineWing-Plugin_Suite-2563EB?style=for-the-badge&logo=github"></a>
</p>

<p align="center">
  <a href="https://minewing.github.io/one-million-crops/">Documentation</a> ·
  <a href="https://github.com/MineWing/one-million-crops/releases/latest">Download</a> ·
  <a href="#commands">Commands</a> ·
  <a href="#placeholderapi">Placeholders</a> ·
  <a href="#live-web-dashboard">Web dashboard</a>
</p>

OneMillionCrops is a server-wide Paper and Folia challenge where everyone contributes toward collecting a configurable target—200,000 per crop in Season 2—of every enabled crop. Progress is persistent, celebrations are shared, and careful provenance tracking keeps automated farms useful without allowing the same stack to be counted repeatedly.

## Folia version

`OneMillionCrops-1.0.25-folia.jar` supports Folia and Paper 1.21.11 with Java 21 or newer. Install only one OneMillionCrops JAR. The plugin keeps the existing `OneMillionCrops` data folder and SQLite format.

Player menus, action bars and effects run on the player's entity scheduler. Cocoa replanting runs on the target region scheduler. Global timers coordinate refreshes and autosaves; database saves use the async scheduler. Harvest summaries and dashboard state are synchronized across regions.

Folia differences:

- The pink sidebar uses bundled FastBoard packets and runs on each player's entity scheduler. It shows overall progress, the 200,000 target and rotating crop pages on both Folia and Paper. Toggle it with `/1mill scoreboard`.
- Plant-wand selections must fit inside the player's currently owned region. A selection outside it is rejected before reading blocks or consuming seeds.
- Install Folia-compatible versions of optional integrations such as PlaceholderAPI.
- Restart the server when installing this build. `/1mill reload` reloads configuration only.

Build with `mvn package`. The shaded JAR in `target/` includes SQLite and FastBoard; no separate scoreboard plugin is needed. All 89 tests pass, covering scheduler routing, disconnected recipients and simultaneous harvests. An isolated Folia 1.21.11 build 14 server passed startup, status, summary, configuration reload and dashboard API checks. Multi-player gameplay testing is still needed for menus, planting and cocoa farms across region boundaries.

## Built for a truly shared challenge

| | |
|---|---|
| **One set of totals** | Every participant contributes to the same crop objectives and combined goal |
| **Accurate counting** | Tracks crop provenance through water, pistons, hoppers, storage, partial pickups, and player drops |
| **Live progress** | Animated `/progress` GUI, rotating scoreboard, action bars, milestones, and completion sequences |
| **Web dashboard** | Responsive dashboard with live totals, velocity, objectives, contributors, online players, and recent pickups |
| **Farm tools** | Storage crop wand and a protected two-point farmland planting wand |
| **Safe persistence** | SQLite transactions, autosaves, per-player contributions, and automatic pre-reset backups |
| **Flexible presentation** | MiniMessage action lists for broadcasts, sounds, particles, titles, boss bars, and fireworks |
| **PlaceholderAPI** | Built-in expansion with global, per-crop, and per-player values |

## Counting that stays honest

The default rules support manual and automatic farms while preventing common recount loops:

- Picking up a stack adds the exact amount that entered the inventory.
- Enabled water farms count at harvest. Enabled piston drops retain their provenance through storage when hopper crediting is enabled.
- Cocoa pods harvested by water or by moving their supporting jungle logs are replanted at age 0, consuming one bean from the drops.
- Eligible crops in chests, barrels, hoppers, and shulker boxes can be inspected or deposited with the Crop Wand.
- Crops deposited by a player are not made eligible again simply by withdrawing them.
- Deliberately dropped items and dispenser/dropper outputs remain ineligible.
- Rebreaking a player-placed crop source does not count until it has genuinely grown.
- Totals clamp exactly at the configured target.

Use `/1m automode water`, `/1m automode pistons` or `/1m automode hoppers` to toggle each source independently. Hoppers default to OFF. `/1m automode` shows the current settings; `/1m automode all` toggles all three together. Participant mode defaults to `EVERYONE`; use `ALLOWLIST` with UUIDs for a closed team.

## Player experience

`/progress` opens an animated inventory containing every enabled crop, its current amount, target, percentage, and completion state. `/1mill scoreboard` provides a compact rotating sidebar, while rapid pickups are combined into a single coloured action-bar update.

At configurable intervals, the plugin broadcasts a ranked harvest summary and shows a draining countdown boss bar. Crop milestones trigger sounds, particles, and announcements. Reaching a crop target starts a persisted completion sequence, and finishing every enabled crop launches a separate grand finale.

## Commands

| Command | Purpose | Permission |
|---|---|---|
| `/progress [crop]` | Open the animated progress GUI | `onemillion.progress` |
| `/1mill status` | Print every crop total | `onemillion.progress` |
| `/1mill scoreboard` | Toggle the live sidebar | `onemillion.progress` |
| `/1mill web` | Show the configured dashboard address | `onemillion.progress` |
| `/1mill wand` | Receive the crop storage wand | `onemillion.wand` |
| `/1mill plantwand` | Receive the two-point farmland planting wand | `onemillion.plantwand` |
| `/1mill crops` | Open the crop enable/disable GUI | `onemillion.admin` |
| `/1mill automode [water\|pistons\|hoppers\|all]` | View or toggle independent farm crediting | `onemillion.admin` |
| `/1mill summary` | Inspect the next harvest summary | `onemillion.admin` |
| `/1mill summary now` | Broadcast the harvest summary immediately | `onemillion.admin` |
| `/1mill backup` | Create a timestamped SQLite backup | `onemillion.admin` |
| `/1mill reload` | Reload supported configuration | `onemillion.admin` |
| `/1mill reset <crop> confirm` | Back up and reset one crop | `onemillion.admin` |
| `/1mill reset confirm` | Back up and reset the complete challenge | `onemillion.admin` |

`onemillion.progress`, `onemillion.wand`, and `onemillion.plantwand` are available to everyone by default. `onemillion.admin` defaults to server operators.

## Install

### Requirements

- Paper 1.21.11
- Java 21 or newer
- PlaceholderAPI 2.12.3 or newer (optional)

1. Download the shaded JAR from the [latest release](https://github.com/MineWing/one-million-crops/releases/latest), or build it from source.
2. Place `OneMillionCrops-*.jar` in the server's `plugins/` directory.
3. Restart Paper.
4. Open the challenge with `/progress`.

The plugin creates `config.yml`, `crops.yml`, `messages.yml`, `progress.db`, and `backups/` under `plugins/OneMillionCrops/`. Always install the shaded `OneMillionCrops-*.jar`; an `original-*.jar` does not include SQLite.

## Configuration

| File | Responsibility |
|---|---|
| `config.yml` | Target, participants, counting rules, scoreboard, web server, autosaves, and celebrations |
| `crops.yml` | Enabled crops, item materials, harvest source blocks, and MiniMessage display names |
| `messages.yml` | Ordered action lists plus configurable GUI and wand lore lists |
| `progress.db` | Shared totals, contributions, completion state, and queued celebrations |
| `backups/` | Manual and automatic pre-reset database backups |

The default catalogue includes wheat, carrots, potatoes, beetroot, nether wart, pumpkins, melon slices, sugar cane, cactus, cocoa beans, bamboo, kelp, berries, chorus fruit, and mushrooms. Crops can be toggled live with `/1mill crops`; disabled progress is preserved.

## PlaceholderAPI

PlaceholderAPI is optional and requires no separate eCloud expansion. Common values include:

| Placeholder | Value |
|---|---|
| `%onemillioncrops_total%` | Total collected across enabled crops |
| `%onemillioncrops_goal%` | Combined target across enabled crops |
| `%onemillioncrops_remaining%` | Remaining amount across the challenge |
| `%onemillioncrops_percent%` | Overall completion percentage |
| `%onemillioncrops_completed_crops%` | Number of completed crops |
| `%onemillioncrops_player_total%` | Viewing player's contribution |
| `%onemillioncrops_crop_<crop>_amount%` | Current amount for a crop |
| `%onemillioncrops_crop_<crop>_remaining%` | Remaining amount for a crop |
| `%onemillioncrops_crop_<crop>_percent%` | Completion percentage for a crop |
| `%onemillioncrops_crop_<crop>_player_amount%` | Viewing player's contribution to a crop |

Numeric placeholders also have `_formatted` variants with thousands separators. Replace `<crop>` with an ID from `crops.yml`, such as `wheat` or `nether_wart`.

## Live web dashboard

The same plugin JAR serves a responsive, read-only React dashboard. Updates arrive over Server-Sent Events without refreshing the page.

| Endpoint | Purpose |
|---|---|
| `/` | Dashboard application |
| `/api/v1/progress` | Current immutable JSON snapshot |
| `/api/v1/events` | Live Server-Sent Events stream |
| `/health` | Lightweight health check |

The listener defaults to `127.0.0.1:8765`. For public access, keep the localhost binding, place an HTTPS reverse proxy in front of it, and configure `web.public-url`. The dashboard exposes no reset, command, database, or server-control endpoint.

## Build from source

```bash
git clone https://github.com/MineWing/one-million-crops.git
cd one-million-crops
cd web && npm ci && npm run build && cd ..
mvn package
```

Node.js 22+ is only required when changing the React frontend. The compiled dashboard is checked into `src/main/resources/web`, so a Java-only `mvn package` includes the latest committed web assets.

The test suite covers target clamping, milestone transitions, contribution tracking, resets, storage wands, planting, database transactions, placeholder values, action parsing, and JSON safety.

---

<p align="center">
  Built by <a href="https://github.com/MineWing">MineWing</a> · See also <a href="https://github.com/MineWing/Rivet">Rivet</a> and <a href="https://github.com/MineWing/EveryBlock">EveryBlock</a>
</p>

## Operator shortcuts

- `/gms`, `/gmc`, `/gmsp` switch your own mode to survival, creative or spectator. Requires `onemillion.gamemode`.
- `/tp <player>` teleports you to an online player.
- `/tphere <player>` brings that player to your current location. Requires `onemillion.teleport`.

Both permissions default to operators. Teleports use Folia's asynchronous teleport API and report cancelled or failed moves. Player names support tab completion. These shortcuts are player-only. The plugin's `/tp` accepts a player name; use `/minecraft:tp` for vanilla coordinates and selectors. If another plugin owns `/tp`, use `/onemillioncrops:tp`.

## Spawn, homes and warps

| Command | Purpose | Default access |
| --- | --- | --- |
| `/setspawn` | Save your position and facing as the server's `/spawn` destination | Operators |
| `/spawn` | Travel to the saved spawn | Everyone |
| `/sethome [name]` | Save or overwrite one of your homes | Everyone |
| `/home [name]` | Travel to one of your homes | Everyone |
| `/delhome [name]` | Remove one of your homes | Everyone |
| `/setwarp <name>` | Save or overwrite a shared warp | Operators |
| `/warp [name]` | Visit a warp, or list warps without a name | Everyone |
| `/delwarp <name>` | Remove a shared warp | Operators |

Omitting a home name uses `home`. Names ignore case and accept 1 to 32 letters, numbers, underscores or hyphens. Saved names support tab completion; homes are scoped to each player's UUID. Setting a location again overwrites it.

Permissions are `onemillion.spawn`, `onemillion.setspawn`, `onemillion.home`, `onemillion.warp`, and `onemillion.warp.admin`. These commands require a player. `/setspawn` sets the `/spawn` destination; it does not change beds, death respawns or first-join behavior.

Locations, world UUIDs and facing are saved in `OneMillionCrops/travel.db`. Back up this file along with `progress.db`; `/1mill backup` backs up crop progress only. Database work runs on an ordered background worker, and shutdown drains accepted writes. Teleporting to an unloaded world reports an error.

Utility and travel commands use pink-and-blush MiniMessage text, chimes and particles. Successful teleports add an end-rod ring, portal particles and a teleport sound for the traveler. Failed or cancelled teleports use error feedback without arrival effects.

## Season 2

The target is **200,000 per crop**. Messages use pink and blush accents with neutral body text; crop colours remain distinct. The menu border and title also use the Season 2 palette.

The first load upgrades older configuration to `challenge.season: 2` and a target of 200,000, and refreshes the message palette. Previous config and messages files are preserved as `config-before-season-2.yml` and `messages-before-season-2.yml`. Subsequent reloads respect your configured target. This update does not reset crop progress or saved travel locations.

## Farm credit controls

The saved settings are `counting.automated-farms.water`, `.pistons`, and `.hoppers`. Existing configurations inherit water/piston settings from the old `allow-automated-farms` value when the new keys are absent. Hopper crediting defaults to false even when the old master setting was true. Explicit source settings take precedence over the legacy value.

Disabled sources still produce ordinary items, but those drops are blocked from challenge credit. Water credit happens at harvest and is independent of hopper transport. Piston drops are tagged when the piston moves, including vertical crops and cocoa beside moving jungle logs. Untraceable ground drops are not credited. When hoppers are off, crops passing through them are marked ineligible for later player pickup or Crop Wand deposits. Already-credited water crops cannot be counted again. Cocoa replanting continues regardless of credit settings.

## Sidebar regression probe

With an isolated offline Folia 1.21.11 server listening on loopback port 25579, run `NODE_PATH=/path/to/minecraft-protocol/node_modules node scripts/scoreboard-probe.cjs`. The probe requires `minecraft-protocol` 1.68.0. It connects a temporary player and fails unless a sidebar display packet and score lines arrive within ten seconds, and the scoreboard can be toggled off and back on. It reproduced zero packets before the fix and received the sidebar and 30 score updates after the fix.

## Timber and mob capture

Rivet's tree detection and snapshot capture are included in OneMillionCrops. Both are enabled by default, including on existing installations. Set `timber.enabled` or `egg-capture.enabled` to `false` in `config.yml` and use `/1mill reload` to disable either feature. No configuration regeneration is needed.

- Timber requires `onemillion.timber`, granted to everyone by default. Break the bottom log with an axe in survival to fell a tree. Sneak for ordinary single-block breaking. Trees must have at least four logs and ten natural leaves or Nether canopy blocks. Scans are capped at 96 logs and 512 canopy blocks, or 256 logs and 1,024 canopy blocks for large jungle trees.
- Every timber block uses a real player break, so protection plugins can cancel breaks, tools wear normally, and drops appear in the world. Felling stops if a break is refused or the axe breaks. The tree is processed immediately, from the canopy down after the initial log, without Rivet's delayed animation. A scan crossing a Folia region boundary falls back to ordinary breaking.
- Mob capture requires `onemillion.eggcapture`, granted to everyone by default. Throw an egg at a supported mob, then collect the captured spawn egg where it stood. Using the spawn egg restores the creature's snapshot, including its name and entity data. Capture completes immediately with particles and sound.
- Capture respects cancelled projectile-hit events and skips NPCs, invulnerable mobs, mobs carrying passengers or riding another entity, and entities without a spawn egg or snapshot. On Folia, the thrower and target must be owned by the current region.

These utilities do not add logs or eggs to the crop challenge catalogue. If Rivet is also installed, disable its corresponding features to avoid overlapping handlers.

## Vein mining and world shortcuts

Vein mining is enabled by default and requires `onemillion.veinminer`, granted to everyone. In survival, break an ore with a suitable pickaxe to mine up to 64 connected blocks of the same ore, including diagonal connections and mixed stone/deepslate variants. Like Rivet, ancient debris and glowstone are supported. Sneak to mine one block. Drops and experience appear normally; each block respects protection events and tool durability. Mining stops when a break is refused or the pickaxe breaks. Oversized veins and scans crossing Folia region boundaries fall back to ordinary breaking. Set `vein-mining.enabled: false` in `config.yml`, then use `/1mill reload`, to disable it. Disable Rivet's vein miner if both plugins are installed.

| Command | Effect in your current world | Permission | Default |
| --- | --- | --- | --- |
| `/day` | Set time to 1,000 ticks | `onemillion.time` | Operators |
| `/night` | Set time to 13,000 ticks | `onemillion.time` | Operators |
| `/sun` | Clear rain and thunder for 12,000 ticks | `onemillion.weather` | Operators |

These shortcuts are player-only and take no arguments. Time changes are immediate. World changes run on Folia's global scheduler, and feedback follows the player's region. If another plugin owns a shortcut, use `/onemillioncrops:day`, `/onemillioncrops:night`, or `/onemillioncrops:sun`.

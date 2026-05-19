# WorldResetPlugin

**Delete and regenerate Minecraft worlds on demand or on a schedule — with full control over what changes, what survives, and who gets notified.**

![Version](https://img.shields.io/badge/version-1.4.0-blue?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Paper%2FSpigot%201.16%2B-brightgreen?style=flat-square)
![Java](https://img.shields.io/badge/java-17%2B-orange?style=flat-square)
![Licence](https://img.shields.io/badge/licence-MIT-lightgrey?style=flat-square)

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Commands](#commands)
- [Permissions](#permissions)
- [Discord Integration](#discord-integration)
- [How Reset Mode Works](#how-reset-mode-works)
- [Schedule Examples](#schedule-examples)
- [Changelog](#changelog)
- [Contributing](#contributing)
- [Licence](#licence)

---

## Features

**Reset & Worlds**
- Two reset modes: `restart` (clean JVM restart via `spigot().restart()`) and `live-regeneration` (worlds deleted and recreated while the server runs)
- Configurable countdown with chat broadcast, title/subtitle display, and ActionBar timer
- Per-world locked seeds written to `level.dat` before every reset so Paper always honours them
- Per-world hardcore mode and custom environment (NORMAL / NETHER / THE_END) overrides
- Per-world custom generator plugin support
- Gamerules, difficulty, world border, and spawn point applied automatically after reset
- Extra paths deleted on reset (`logs`, `playerdata`, etc.) with configurable preserve list
- Player data clearing: independent toggles for inventory, ender chest, XP, health, hunger, advancements, and stats
- ZIP-based world backups before deletion, with configurable archive count and auto-pruning

**Automation & Safety**
- `interval` schedule (every N seconds) and `daily` schedule (HH:MM in any IANA timezone)
- Safety floor (`schedule.min-interval-seconds`) prevents dangerously short cycles
- Configurable cooldown between resets; `worldreset.bypass-cooldown` permission to skip
- Two-step confirmation (`/worldreset start` → `/worldreset start --confirm`) with 30-second expiry
- `--now` flag for instant execution, skipping the countdown
- Pre/post-reset console commands with optional strict mode (abort on failure)
- Whitelist auto-enabled during reset, auto-disabled after
- Async world deletion with NIO `walkFileTree` and retry loop
- `server.properties` patching applied on every restart-mode reset
- Community vote-to-reset with configurable required percentage, duration, and minimum player count
- Persistent reset history log (`reset-history.log`)
- Async update checker against the GitHub Releases API — notifies ops in-game on join

**Notifications & Ops**
- Discord webhook: startup template (sent on every server start, distinguishes post-reset vs normal) and start template (sent when a reset is confirmed)
- Countdown milestone Discord messages at configurable seconds-remaining thresholds
- 23 named `{placeholder}` tokens across all templates
- Paginated in-game help (`/worldreset help [1-5]`) with clickable navigation
- Rich clickable chat UI for worlds, seeds, gamerules, and server properties lists
- Grouped browsable view of all 54 standard `server.properties` keys with live values and clickable `[Set]` / `[X]` buttons

---

## Requirements

- Paper or Spigot 1.16 or later
- Java 17 or later
- No external dependencies — pure Java, no NMS imports

---

## Installation

1. Download the `WorldResetPlugin-1.4.0.jar` from the [releases page](../../releases/latest).
2. Drop the JAR into your server's `plugins/` directory.
3. Start (or restart) the server — the plugin generates `config.yml` and companion files automatically.
4. Edit `plugins/WorldResetPlugin/config.yml` to configure your worlds, schedule, and Discord webhook.
5. Run `/worldreset reload` in-game or from the console to apply your changes without a full restart.

---

## Configuration

The plugin generates three configuration files on first run:

| File | Purpose |
|---|---|
| `config.yml` | All general settings — worlds, countdown, gamerules, schedule, Discord, etc. |
| `server-properties.yml` | Key→value patches written to `server.properties` on every restart-mode reset |
| `advanced.yml` | Per-world environment overrides and custom generator plugins |

All values in `config.yml` can be read or changed in-game with `/worldreset config get <key>` and `/worldreset config set <key> <value>`.

### Key `config.yml` options

```yaml
reset:
  countdown: 10               # Seconds of countdown before the reset fires
  cooldown-seconds: 300       # Minimum seconds between resets (0 = no cooldown)

worlds:
  - world
  - world_nether
  - world_the_end

seeds: {}          # e.g.  world: 123456789
hardcore: {}       # e.g.  world: true

difficulty: HARD

gamerules:
  naturalRegeneration: "false"
  keepInventory: "false"

schedule:
  enabled: false
  mode: "interval"          # interval | daily
  interval-seconds: 86400
  daily-time: "04:00"
  timezone: "UTC"

backup:
  enabled: false
  keep: 5

player-data:
  enabled: false

notifications:
  discord-webhook: ""

vote:
  enabled: false
  required-percent: 60
  vote-duration-seconds: 60
  min-players: 3
```

---

## Commands

### `/worldreset` (aliases: `wr`, `wrs`, `wreset`)

| Sub-command | Aliases | Permission | Description |
|---|---|---|---|
| `help [page]` | — | `worldreset.use` | Show paginated help (5 pages) |
| `start [--now] [--confirm]` | `s` | `worldreset.start` | Begin a reset. First use shows a confirmation warning; second use (or `--confirm`) executes. `--now` skips the countdown. |
| `cancel` | `c` | `worldreset.cancel` | Cancel the current countdown |
| `status` | `st` | `worldreset.status` | Show current reset state and key config values |
| `reload` | `r`, `rl` | `worldreset.reload` | Reload all config files (blocked during an active reset) |
| `worlds [add\|remove] [name]` | `w` | `worldreset.admin` | List or modify the reset world list |
| `seed <world> [value\|random\|lock\|unlock]` | — | `worldreset.admin` | Interactive per-world seed management |
| `hardcore <world> [true\|false\|list]` | — | `worldreset.admin` | Toggle hardcore mode per world |
| `gamerule [rule] [value\|remove]` | `gr` | `worldreset.admin` | List or set gamerules applied after reset |
| `environment <world> [env\|clear]` | `env` | `worldreset.admin` | Override world environment (NORMAL / NETHER / THE_END) |
| `generator <world> [plugin\|clear]` | `gen` | `worldreset.admin` | Assign a custom generator plugin to a world |
| `config [set\|get] [key] [value]` | `cfg` | `worldreset.admin` | Read or write any config key in-game |
| `delete [add\|remove] [path]` | — | `worldreset.admin` | Manage extra paths deleted on reset |
| `preserve [add\|remove] [path]` | — | `worldreset.admin` | Manage paths preserved across deletion |
| `schedule [enable\|disable\|mode\|interval\|daily\|status]` | `sched` | `worldreset.schedule` | View or configure the auto-reset schedule |
| `backup [now\|enable\|disable\|keep\|dir]` | `bk` | `worldreset.backup` | Manage and trigger backups |
| `history [n]` | `hist` | `worldreset.history` | Show last n reset history entries (default 10) |
| `serverprops [set\|remove\|enable\|disable\|reload\|list]` | `sp` | `worldreset.admin` | Manage the `server.properties` patch list |
| `props [key] [value\|remove\|enable\|disable\|reload]` | `p`, `prop` | `worldreset.admin` | Grouped browser for all 54 standard `server.properties` keys |

### `/resetvote` (aliases: `rv`, `vote-reset`, `voterestart`)

| Usage | Permission | Description |
|---|---|---|
| `/resetvote` | `worldreset.vote` | Start a community vote to reset the world |
| `/resetvote yes` | `worldreset.vote` | Cast a yes vote during an active vote |

---

## Permissions

| Permission | Default | Description |
|---|---|---|
| `worldreset.admin` | op | Full access; bypasses all sub-permission checks |
| `worldreset.use` | op | Required to run any `/worldreset` sub-command |
| `worldreset.start` | op | Start a world reset |
| `worldreset.cancel` | op | Cancel a running reset |
| `worldreset.status` | op | View reset status and settings |
| `worldreset.reload` | op | Reload plugin config |
| `worldreset.schedule` | op | View and manage the auto-reset schedule |
| `worldreset.backup` | op | Manage and trigger backups |
| `worldreset.history` | op | View the reset history log |
| `worldreset.vote` | **true** (all players) | Start or participate in a community reset vote |
| `worldreset.bypass-cooldown` | op | Bypass the reset cooldown |

---

## Discord Integration

The plugin sends two distinct webhook notifications. The `discord-startup-template` fires asynchronously every time the server starts — covering both post-reset restarts and normal starts — and includes who triggered the reset and when. The `discord-start-template` fires the moment a reset is confirmed, before the countdown completes, so your team sees every setting that will change.

### Example `discord-start-template` output

🔄 **World Reset STARTED** — **MyServer**
**Initiated by:** Steve  |  **Mode:** restart  |  **Time:** 2025-05-16 04:00:01
─────────────────────────────
🌍 **Worlds:**
world (NORMAL | seed: 123456789 locked | hardcore: no)
world_nether (NETHER | seed: random | hardcore: no)
world_the_end (THE_END | seed: random | hardcore: no)
─────────────────────────────
⚙️ **Settings applied after reset:**
📋 **Gamerules:** naturalRegeneration=false, keepInventory=false
💀 **Difficulty:** HARD
☠ **Hardcore:** world: no, world_nether: no, world_the_end: no
🗺️ **World border:** enabled, size: 1000.0, center: 0,0
📍 **Spawn:** enabled, world, 0/64/0
─────────────────────────────
🗑️ **Data cleared:**
🧹 **Player data:** inventory, XP, health, hunger, advancements, stats
📁 **Extra paths deleted:** logs, playerdata, advancements, stats
🔒 **Preserved paths:** (none)
─────────────────────────────
🛠️ **server.properties patches:** difficulty=hard, pvp=true
▶ **Pre-reset commands:** (none)
◀ **Post-reset commands:** (none)
💾 **Backup:** enabled (keep 5)  |  🔐 **Whitelist:** disabled
🗳️ **Vote result:** N/A  |  **Voters:** N/A

### Available placeholders

The following `{placeholder}` tokens are available across the webhook templates:

| Placeholder | Resolves to |
|---|---|
| `{server}` | Server name from `server.properties` |
| `{initiator}` | Player name, `Schedule`, or `Vote` |
| `{worlds}` | Comma-separated list of worlds being reset |
| `{worlds_detail}` | Per-world: environment, seed (locked/random), hardcore flag |
| `{mode}` | `restart` or `live-regeneration` |
| `{time}` | Date/time the reset occurred (server timezone) |
| `{gamerules}` | Configured gamerule key=value pairs |
| `{difficulty}` | Configured difficulty |
| `{seeds}` | Locked seeds summary; `(none — random each reset)` if none |
| `{hardcore}` | Per-world hardcore flag |
| `{extra_paths}` | Paths deleted on reset |
| `{preserve_paths}` | Paths kept across the reset |
| `{voter_list}` | Display names of players who voted yes |
| `{vote_result}` | e.g. `8 voted yes`; `N/A` for non-vote resets |
| `{player_data}` | What player data is cleared |
| `{backup}` | Backup status: `enabled (keep N)` or `disabled` |
| `{world_border}` | World border config (enabled/disabled, size, center) |
| `{spawn}` | Spawn config (enabled/disabled, world, xyz) |
| `{server_props}` | `server.properties` patches applied after restart |
| `{pre_commands}` | Commands run before the reset starts |
| `{post_commands}` | Commands run after the reset finishes |
| `{whitelist}` | Whitelist behaviour during reset |
| `{duration}` | How long the reset took (startup template only) |
| `{reason}` | `World Reset` or `Normal Start` (startup template only) |
| `{reset_initiator}` | Who triggered the reset (startup template only) |
| `{reset_time}` | Timestamp when the reset began (startup template only) |

Templates support YAML block scalars (`|`) and `\n` for line breaks. All template strings are fully customisable in `config.yml`.

> **Note:** The `discord-complete-template` (a post-reset completion message) was intentionally removed from automatic sending in 1.4.0. The `discord-startup-template` now serves this purpose — it fires when the server comes back online and identifies the restart as a `World Reset` start rather than a `Normal Start`.

---

## How Reset Mode Works

### Restart mode (`use-restart: true`)

The plugin kicks all players, deletes the configured worlds, writes a minimal `level.dat` with the locked seed and hardcore flag for each world, and patches `server.properties` from the configured patch list. It then calls `spigot().restart()` and writes a `pending-post-reset.flag` file containing the initiator name and reset timestamp. On the next startup, the plugin detects this flag, deletes it, and applies gamerules, difficulty, world border, and spawn point in a deferred two-tick task. The Discord startup notification reads the flag values so `{reason}`, `{reset_initiator}`, and `{reset_time}` are always accurate.

### Live-regeneration mode (`use-restart: false`)

The plugin unloads the configured worlds, deletes their directories asynchronously using NIO `walkFileTree`, pre-writes a `level.dat` for any worlds with locked seeds, then calls `Bukkit.createWorld()` on the main thread for each world. Gamerules, difficulty, world border, and spawn point are applied immediately after the new worlds are loaded — no restart or deferred flag is needed.

> **Recommendation:** Use restart mode on production servers. It guarantees a clean JVM state, respects Paper's world-loading pipeline, and avoids edge cases around plugin state that can occur when worlds are torn down and recreated at runtime.

---

## Schedule Examples

```yaml
# Example 1 — Reset every 24 hours (interval mode)
schedule:
  enabled: true
  mode: "interval"
  interval-seconds: 86400   # 24 × 60 × 60
  timezone: "UTC"           # not used in interval mode
```

```yaml
# Example 2 — Reset daily at 04:00 New York time (daily mode)
schedule:
  enabled: true
  mode: "daily"
  daily-time: "04:00"
  timezone: "America/New_York"   # any IANA zone ID
```

```yaml
# Example 3 — Reset every 7 days (interval mode)
schedule:
  enabled: true
  mode: "interval"
  interval-seconds: 604800   # 7 × 24 × 60 × 60
```

---

## Changelog

| Version | Highlights |
|---|---|
| 1.4.0 | Startup Discord notification replaces complete notification; `pending-post-reset.flag` now stores initiator and timestamp for accurate post-restart reporting |
| 1.3.0 | `discord-start-template` expanded to 23 placeholders covering every setting changed by the reset; `/worldreset props` grouped browser for all 54 standard `server.properties` keys |
| 1.2.0 | Async update checker against GitHub Releases API; per-world custom environment and generator plugin support via `advanced.yml` |
| 1.1.0 | Community vote-to-reset (`/resetvote`); configurable `min-players` and `required-percent`; vote result and voter list passed to Discord templates |
| 1.0.0 | Initial release: restart and live-regeneration modes, per-world seeds, gamerules, schedule, ZIP backups, and `server.properties` patching |

Full changelog: [CHANGELOG.md](CHANGELOG.md)

---

## Contributing

Pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the coding rules (no NMS, no reflection, no silent exception swallowing, and all public manager methods require Javadoc).

---

## Licence

[MIT](LICENSE)

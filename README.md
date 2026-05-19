# WorldResetPlugin

**Delete and regenerate Minecraft worlds on demand or on a schedule — with full control over what changes, what survives, and who gets notified.**

![Version](https://img.shields.io/badge/version-1.4.4-blue?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Paper%2FSpigot%201.16%20--%201.21-brightgreen?style=flat-square)
![Java](https://img.shields.io/badge/java-17%20--%2025-orange?style=flat-square)
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
- **Reliable Seeds:** Per-world seeds (locked or random) are pre-written to `level.dat` before every reset using a robust legacy-upgrade method, ensuring Paper 1.16–1.21+ always honours them.
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
- **High-Priority Patching:** `server.properties` patches are written *before* world generation and correctly override dedicated config settings (difficulty, hardcore, seed).
- Community vote-to-reset with configurable required percentage, duration, and minimum player count
- Persistent reset history log (`reset-history.log`)
- Async update checker against the GitHub Releases API — notifies ops in-game on join

**Notifications & Ops**
- Discord webhook: startup template (sent on every server start, distinguishes post-reset vs normal) and start template (sent when a reset is confirmed)
- Countdown milestone Discord messages at configurable seconds-remaining thresholds
- 23 named `{placeholder}` tokens across all templates
- **Enhanced Notifications:** `{server_props}` now includes per-world hardcore, environment, and seed changes in the Discord report.
- Paginated in-game help (`/worldreset help [1-5]`) with clickable navigation
- Rich clickable chat UI for worlds, seeds, gamerules, and server properties lists
- **Standard Property Commands:** Direct subcommands for all 54 standard `server.properties` keys via `/worldreset props <key> <value>`.

---

## Requirements

- Paper or Spigot 1.16 or later (Fully tested on **1.21.11**)
- Java 17 to **25** (OpenJDK)
- No external dependencies — pure Java, no NMS imports

---

## Installation

1. Download the `WorldResetPlugin-1.4.4.jar` from the [releases page](../../releases/latest).
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
| `server-properties.yml` | Key→value patches written to `server.properties` on every reset |
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

difficulty: hard

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
| `gamerule [rule] [value\|remove]` | `gr` | `worldreset.admin` | List or set gamerules applied after reset (Hardcore managed via `/wr hardcore`) |
| `environment <world> [env\|clear]` | `env` | `worldreset.admin` | Override world environment (NORMAL / NETHER / THE_END) |
| `generator <world> [plugin\|clear]` | `gen` | `worldreset.admin` | Assign a custom generator plugin to a world |
| `config [set\|get] [key] [value]` | `cfg` | `worldreset.admin` | Read or write any config key in-game |
| `delete [add\|remove] [path]` | — | `worldreset.admin` | Manage extra paths deleted on reset |
| `preserve [add\|remove] [path]` | — | `worldreset.admin` | Manage paths preserved across deletion |
| `schedule [enable\|disable\|mode\|interval\|daily\|status]` | `sched` | `worldreset.schedule` | View or configure the auto-reset schedule |
| `backup [now\|enable\|disable\|keep\|dir]` | `bk` | `worldreset.backup` | Manage and trigger backups |
| `history [n]` | `hist` | `worldreset.history` | Show last n reset history entries (default 10) |
| `serverprops [set\|remove\|enable\|disable\|reload\|list]` | `sp` | `worldreset.admin` | Manage the `server.properties` patch list |
| `props [key] [value\|remove\|enable\|disable\|reload]` | `p`, `prop` | `worldreset.admin` | Direct access to all 54 standard keys (e.g., `/wr p pvp false`) |

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
💀 **Difficulty:** hard
☠ **Hardcore:** world: no, world_nether: no, world_the_end: no
🗺️ **World border:** enabled, size: 1000.0, center: 0,0
📍 **Spawn:** enabled, world, 0/64/0
─────────────────────────────
🗑️ **Data cleared:**
🧹 **Player data:** inventory, XP, health, hunger, advancements, stats
📁 **Extra paths deleted:** logs, playerdata, advancements, stats
🔒 **Preserved paths:** (none)
─────────────────────────────
🛠️ **server.properties patches:** difficulty=hard, pvp=true, seed.world=123456789
▶ **Pre-reset commands:** (none)
◀ **Post-reset commands:** (none)
💾 **Backup:** enabled (keep 5)  |  🔐 **Whitelist:** disabled
🗳️ **Vote result:** N/A  |  **Voters:** N/A

---

## How Reset Mode Works

### Restart mode (`use-restart: true`)

The plugin kicks all players, deletes the configured worlds, writes a minimal legacy-format `level.dat` (DataVersion 2230) with the seed and hardcore flag, and patches `server.properties`. It then calls `spigot().restart()`. On the next startup, Paper detects the legacy `level.dat`, automatically upgrades it to the 1.21+ format (generating the correct dimensions), and applies gamerules/difficulty. This method guarantees that seeds are always honoured, even on modern Paper builds.

### Live-regeneration mode (`use-restart: false`)

The plugin unloads worlds, deletes directories asynchronously, pre-writes the legacy `level.dat` for **all** worlds (ensuring random seeds are genuine even if file deletion is incomplete), then calls `Bukkit.createWorld()`. Gamerules, difficulty, and other settings are applied immediately.

---

## Changelog

| Version | Highlights |
|---|---|
| **1.4.4** | **ServerProps Priority:** `serverprops` patches now override dedicated config settings and are written *pre-generation* in all modes. |
| **1.4.3** | **Paper 1.21 Fix:** Reverted `level.dat` to legacy NBT format to resolve the `No key dimensions` startup crash on modern Paper versions. |
| **1.4.2** | **Paper 1.21 Support:** Added `DataVersion` to NBT and `api-version: '1.21'` to `plugin.yml` to resolve legacy warnings and loading errors. |
| **1.4.1** | **Direct Prop Commands:** Added subcommands for all 54 standard server properties; removed synthetic `hardcore` gamerule row in favour of dedicated command. |
| 1.4.0 | Startup Discord notification replaces complete notification; `pending-post-reset.flag` now stores initiator and timestamp for accurate reporting. |
| 1.3.0 | `discord-start-template` expanded to 23 placeholders; `/worldreset props` grouped browser for all 54 standard `server.properties` keys. |

Full changelog: [CHANGELOG.md](CHANGELOG.md)

---

## Contributing

Pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the coding rules.

---

## Licence

[MIT](LICENSE)

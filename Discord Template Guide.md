# WorldResetPlugin — Discord Template Guide
> v1.4.0 — startup notification, expanded start template, complete template retired

---

## What changed in 1.4.0

| # | Change | Where | Status |
|---|--------|-------|--------|
| 1 | **`discord-startup-template` added** — sent on every server start, distinguishes `"World Reset"` from `"Normal Start"` | `WorldResetPlugin.java` (`onEnable`) | ✅ Added |
| 2 | **`discord-complete-template` no longer auto-sent** — startup notification replaces it; complete template kept for custom integrations | `ResetManager.java` | ✅ Changed |
| 3 | **`pending-post-reset.flag` now stores `initiator` and `reset_time`** — startup template reads these so `{reset_initiator}` and `{reset_time}` are always accurate after a restart | `ResetManager.java` | ✅ Fixed |
| 4 | **Start template expanded to 23 placeholders** — now covers hardcore, world border, spawn, preserved paths, server.properties patches, pre/post commands, and whitelist | `config.yml` | ✅ Added |

---

## How the Discord messages now work

```
Reset starts (countdown fires OR instant reset)
    │
    └─► Discord START message sent immediately (async, non-blocking)
    └─► History log: STARTED entry written

  [countdown ticks]
    └─► Discord milestone warnings at 60s / 30s / 10s

Reset executes
    │
    ├── [live-regeneration mode] worlds regenerate on main thread
    │       └─► (no complete message — startup template handles it on next boot)
    │
    └── [restart mode] server.properties patched → level.dat pre-written
            └─► pending-post-reset.flag written (initiator + reset_time)
                └─► spigot().restart() called

Server comes back online
    └─► onEnable() reads pending-post-reset.flag
            └─► Discord STARTUP message sent (reason = "World Reset",
                {reset_initiator} and {reset_time} populated from flag)
                └─► flag deleted

Normal server start (no reset)
    └─► Discord STARTUP message sent (reason = "Normal Start",
        {reset_initiator} = "N/A", {reset_time} = "N/A")

History log entries written for all three events: STARTED, RESET, CANCELLED
```

---

## History log format

The history file lives at:
```
plugins/WorldResetPlugin/reset-history.log
```

Each event writes one line:

```
[2025-05-16 04:00:01] STARTED  | Triggered by: Schedule | Worlds: world, world_nether, world_the_end
[2025-05-16 04:00:49] RESET    | Triggered by: Schedule | Mode: restart | Worlds: world, world_nether, world_the_end | Duration: 47s
[2025-05-16 03:30:12] STARTED  | Triggered by: Steve | Worlds: world
[2025-05-16 03:30:20] CANCELLED| By: Steve | Seconds remaining: 5
[2025-05-16 05:00:00] STARTED  | Triggered by: Vote | Worlds: world
[2025-05-16 05:00:48] RESET    | Triggered by: Vote | Mode: live-regeneration | Worlds: world | Duration: 48s
```

In-game colours:
- **Cyan** (`§b`) — STARTED entries
- **Green** (`§a`) — RESET (completed) entries
- **Yellow** (`§e`) — CANCELLED entries

View with:
```
/worldreset history        → last 10 entries
/worldreset history 25     → last 25 entries
```

---

## Discord template placeholders

### Startup template (`discord-startup-template`)

Sent on every `onEnable()` — both post-reset restarts and normal starts.

| Placeholder | What it shows | Example value |
|-------------|---------------|---------------|
| `{server}` | Bukkit server name | `MySurvivalServer` |
| `{time}` | Date/time this startup occurred | `2025-05-16 04:00:55` |
| `{reason}` | Why the server started | `World Reset` or `Normal Start` |
| `{reset_initiator}` | Who triggered the reset | `Steve`, `Schedule`, `Vote`, or `N/A` |
| `{reset_time}` | Timestamp when the reset began | `2025-05-16 04:00:01` or `N/A` |

### Start template (`discord-start-template`)

Sent immediately when a reset is confirmed (at the start of the countdown or on instant fire).

| Placeholder | What it shows | Example value |
|-------------|---------------|---------------|
| `{server}` | Bukkit server name | `MySurvivalServer` |
| `{initiator}` | Who triggered the reset | `Steve`, `Schedule`, `Vote` |
| `{mode}` | Reset execution mode | `restart` or `live-regeneration` |
| `{time}` | Date/time reset was triggered | `2025-05-16 04:00:01` |
| `{worlds}` | Worlds being reset (comma list) | `world, world_nether, world_the_end` |
| `{worlds_detail}` | Per-world: environment, seed, hardcore | `world [NORMAL] seed=random hardcore=no` |
| `{seeds}` | Locked seeds summary | `world: 8675309` or `(none — random each reset)` |
| `{hardcore}` | Per-world hardcore flag | `world: no, world_nether: no` |
| `{difficulty}` | Configured difficulty | `HARD` |
| `{gamerules}` | Configured gamerule key=value pairs | `naturalRegeneration=false, keepInventory=false` |
| `{world_border}` | World border config | `enabled, size: 1000.0, center: 0,0` or `disabled` |
| `{spawn}` | Spawn config | `enabled, world, 0/64/0` or `disabled` |
| `{player_data}` | Player data cleared on reset | `inventory, XP, health, hunger` or `disabled` |
| `{extra_paths}` | Extra paths deleted on reset | `logs, playerdata, advancements, stats` |
| `{preserve_paths}` | Paths kept across the reset | `config/mydata` or `(none)` |
| `{server_props}` | server.properties patches applied | `difficulty=hard, pvp=true` |
| `{pre_commands}` | Commands run before the reset | `say Resetting!` or `(none)` |
| `{post_commands}` | Commands run after the reset | `say Done!` or `(none)` |
| `{whitelist}` | Whitelist behaviour during reset | `enabled during reset` or `disabled` |
| `{backup}` | Backup status | `enabled (keep 5)` or `disabled` |
| `{vote_result}` | Vote count summary | `5 voted yes` or `N/A` |
| `{voter_list}` | Names of yes-voters | `Alice, Bob, Carol` or `N/A` |

### Complete template (`discord-complete-template`)

> ⚠️ **This template is no longer sent automatically as of 1.4.0.**
> The `discord-startup-template` now serves this purpose.
> The complete template is kept in `config.yml` for custom integrations —
> call `sendDiscordWebhook()` in your own code to use it.

| Placeholder | What it shows | Example value |
|-------------|---------------|---------------|
| All start template placeholders | (same as above) | — |
| `{duration}` | How long the reset took | `47s`, `2m 47s` |

> **Note:** `{duration}` is only meaningful in the complete template.
> In the start template it always shows `in progress` because the reset has not finished yet.

---

## How to customise the templates

Open `plugins/WorldResetPlugin/config.yml` and find the `notifications:` section.

### Method 1 — YAML block scalar (recommended, easiest to read)

```yaml
notifications:
  discord-webhook: "https://discord.com/api/webhooks/YOUR_WEBHOOK_HERE"

  discord-startup-template: |
    🟢 **Server Online** — **{server}**
    **Reason:** {reason}
    **Reset started by:** {reset_initiator}  |  **Reset began at:** {reset_time}
    ⏰ **Online at:** {time}

  discord-start-template: |
    🔄 **World Reset STARTED** — **{server}**
    **Initiated by:** {initiator}  |  **Mode:** {mode}  |  **Time:** {time}
    🌍 **Worlds:** {worlds_detail}
    📋 **Gamerules:** {gamerules}
    💀 **Difficulty:** {difficulty}
    ☠ **Hardcore:** {hardcore}
    🗺️ **World border:** {world_border}
    📍 **Spawn:** {spawn}
    🧹 **Player data:** {player_data}
    📁 **Extra paths deleted:** {extra_paths}
    🔒 **Preserved paths:** {preserve_paths}
    🛠️ **server.properties patches:** {server_props}
    ▶ **Pre-reset commands:** {pre_commands}
    ◀ **Post-reset commands:** {post_commands}
    💾 **Backup:** {backup}  |  🔐 **Whitelist:** {whitelist}
    🗳️ **Vote result:** {vote_result}  |  **Voters:** {voter_list}
```

The `|` tells YAML "everything indented below me is a multi-line string".
Each physical line becomes a Discord line break.

### Method 2 — Single-line with `\n`

```yaml
  discord-startup-template: "🟢 **{server}** is online — Reason: {reason}\n🕐 {time}"
  discord-start-template: "🔄 Reset on **{server}** by {initiator}\n🌍 {worlds}\n🌱 Seeds: {seeds}"
```

### Method 3 — Leave empty to use the built-in default

```yaml
  discord-startup-template: ""
  discord-start-template: ""
```

When the value is empty the plugin uses its built-in default template.

---

## Reload after editing

```
/worldreset reload
```

No server restart needed.

---

## Setting the webhook URL in-game

```
/worldreset config set notifications.discord-webhook https://discord.com/api/webhooks/...
```

---

## Example Discord output (default templates)

### Startup message — post-reset restart
```
🟢 Server Online — MySurvivalServer
Reason: World Reset
Reset started by: Steve  |  Reset began at: 2025-05-16 04:00:01
⏰ Online at: 2025-05-16 04:00:55
```

### Startup message — normal start
```
🟢 Server Online — MySurvivalServer
Reason: Normal Start
Reset started by: N/A  |  Reset began at: N/A
⏰ Online at: 2025-05-16 12:00:03
```

### Start message
```
🔄 World Reset STARTED — MySurvivalServer
Initiated by: Steve  |  Mode: restart  |  Time: 2025-05-16 04:00:01
─────────────────────────────
🌍 Worlds:
world (NORMAL | seed: 8675309 locked | hardcore: no)
world_nether (NETHER | seed: random | hardcore: no)
world_the_end (THE_END | seed: random | hardcore: no)
─────────────────────────────
⚙️ Settings applied after reset:
📋 Gamerules: naturalRegeneration=false, keepInventory=false
💀 Difficulty: HARD
☠ Hardcore: world: no, world_nether: no, world_the_end: no
🗺️ World border: enabled, size: 1000.0, center: 0,0
📍 Spawn: enabled, world, 0/64/0
─────────────────────────────
🗑️ Data cleared:
🧹 Player data: inventory, XP, health, hunger
📁 Extra paths deleted: logs, playerdata, advancements, stats
🔒 Preserved paths: (none)
─────────────────────────────
🛠️ server.properties patches: difficulty=hard, pvp=true
▶ Pre-reset commands: (none)
◀ Post-reset commands: (none)
💾 Backup: enabled (keep 5)  |  🔐 Whitelist: disabled
🗳️ Vote result: N/A  |  Voters: N/A
```

### Start message — vote-triggered reset
```
🔄 World Reset STARTED — MySurvivalServer
Initiated by: Vote  |  Mode: restart  |  Time: 2025-05-16 05:00:00
...
🗳️ Vote result: 5 voted yes  |  Voters: Alice, Bob, Carol, Dave, Eve
```

---

## Files changed in this patch

| File | Change summary |
|------|----------------|
| `WorldResetPlugin.java` | `onEnable()` reads `pending-post-reset.flag` for `initiator` and `reset_time`; sends `discord-startup-template` async on every start |
| `ResetManager.java` | `discord-complete-template` removed from auto-send flow; `pending-post-reset.flag` now writes both `initiator=` and `reset_time=` lines |
| `ConfigManager.java` | Added `getDiscordStartupTemplate()`; start template expanded with 10 new placeholders |
| `config.yml` | `discord-startup-template` added; `discord-start-template` updated with full 23-placeholder set; `discord-complete-template` marked as no longer auto-sent |

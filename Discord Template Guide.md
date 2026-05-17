# WorldResetPlugin — Discord Template Guide
> v1.3.0 — covers all changes in this patch

---

## What was broken and what was fixed

| # | Bug / Feature | Where | Status |
|---|---------------|-------|--------|
| 1 | **Discord complete message never sent in restart mode** | `ResetManager.java` (restart `finally` block) | ✅ Fixed |
| 2 | **Discord start message fired mid-reset** (after world deletion, not at the actual start) | `ResetManager.java` (`asyncWork`) | ✅ Fixed |
| 3 | **History log had no STARTED entry** — only RESET and CANCELLED | `HistoryManager.java` | ✅ Fixed |
| 4 | **Voter names not tracked anywhere** — vote resets just said "Vote" with no detail | `VoteManager.java` | ✅ Fixed |
| 5 | **No rich Discord template** — both messages were plain single-line strings | All files | ✅ Added |

---

## How the Discord messages now work

```
Reset starts (countdown fires OR instant reset)
    │
    └─► Discord START message sent immediately (async, non-blocking)
    └─► History log: STARTED entry written

  [countdown ticks]
    └─► Discord milestone warnings at 60s / 30s / 10s (existing)

Reset executes
    │
    ├── [live-regeneration mode] worlds regenerate on main thread
    │       └─► Discord COMPLETE sent async after broadcast
    │
    └── [restart mode] server.properties patched → level.dat pre-written
            └─► Discord COMPLETE sent SYNCHRONOUSLY before spigot().restart()
                (must be sync because async tasks die when JVM shuts down)

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

These tokens work in both `discord-start-template` and `discord-complete-template`
inside `config.yml`.

| Placeholder | What it shows | Example value |
|-------------|---------------|---------------|
| `{server}` | Bukkit server name | `MySurvivalServer` |
| `{initiator}` | Who triggered the reset | `Steve`, `Schedule`, `Vote` |
| `{worlds}` | Worlds being reset (comma list) | `world, world_nether, world_the_end` |
| `{worlds_detail}` | Each world with environment + seed | `world [NORMAL] seed=random` |
| `{mode}` | Reset execution mode | `restart` or `live-regeneration` |
| `{time}` | Date/time reset occurred | `2025-05-16 04:00:01` |
| `{gamerules}` | Configured gamerules | `naturalRegeneration=false, keepInventory=false` |
| `{difficulty}` | Configured difficulty | `HARD` |
| `{seeds}` | Worlds with locked seeds | `world: 8675309` or `(none — random each reset)` |
| `{extra_paths}` | Extra paths deleted on reset | `logs, playerdata, advancements, stats` |
| `{voter_list}` | Names of yes-voters | `Alice, Bob, Carol` or `N/A` |
| `{vote_result}` | Vote count summary | `3 voted yes` or `N/A` |
| `{player_data}` | Player data cleared on reset | `inventory, XP, health, hunger` or `disabled` |
| `{backup}` | Backup status | `enabled (keep 5)` or `disabled` |
| `{duration}` | ⚠️ **Complete message only** — how long the reset took | `2m 47s` |

> **Note:** `{duration}` in the start template always shows `in progress` because
> the duration isn't known yet. Only use it in `discord-complete-template`.

---

## How to customize the templates

Open `plugins/WorldResetPlugin/config.yml` and find the `notifications:` section.

### Method 1 — YAML block scalar (recommended, easiest to read)

```yaml
notifications:
  discord-webhook: "https://discord.com/api/webhooks/YOUR_WEBHOOK_HERE"

  discord-start-template: |
    🔄 **Reset starting on {server}!**
    Started by **{initiator}** in mode `{mode}`
    Worlds: {worlds}
    Seeds: {seeds}
    Vote info: {vote_result} — {voter_list}

  discord-complete-template: |
    ✅ **Reset done on {server}!**
    Took: {duration}
    Worlds reset: {worlds_detail}
```

The `|` tells YAML "everything indented below me is a multi-line string".
Each physical line becomes a Discord line break.

### Method 2 — Single-line with `\n`

```yaml
  discord-start-template: "🔄 Reset on **{server}** by {initiator}\n🌍 {worlds}\n🌱 Seeds: {seeds}"
```

### Method 3 — Leave empty to use the built-in Java default

```yaml
  discord-start-template: ""
```

When the value is empty the plugin uses a sensible default that shows all details.

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

## Example Discord output (default template)

### Start message
```
🔄 World Reset STARTED — MySurvivalServer
Initiated by: Steve
Worlds: world, world_nether, world_the_end
Mode: restart  |  Difficulty: HARD
─────────────────────────────
🌱 Seeds (locked): world: 8675309
📋 Gamerules: naturalRegeneration=false, keepInventory=false
🗑️ Extra paths deleted: logs, playerdata, advancements, stats
🧹 Player data cleared: inventory, XP, health, hunger
💾 Backup: enabled (keep 5)
🗳️ Vote result: N/A  |  Voters: N/A
⏰ Time: 2025-05-16 04:00:01
```

### Complete message
```
✅ World Reset COMPLETE — MySurvivalServer
Initiated by: Steve  |  Mode: restart
Worlds: world, world_nether, world_the_end
─────────────────────────────
🗺️ World detail:
world [NORMAL] seed=8675309
world_nether [NETHER] seed=random
world_the_end [THE_END] seed=random
⏱️ Duration: 47s  |  Time: 2025-05-16 04:00:48
```

### Vote-triggered reset (start message)
```
🔄 World Reset STARTED — MySurvivalServer
Initiated by: Vote
...
🗳️ Vote result: 5 voted yes  |  Voters: Alice, Bob, Carol, Dave, Eve
```

---

## Files changed in this patch

| File | Change summary |
|------|----------------|
| `ResetManager.java` | Fix restart Discord complete; move start send to top of `executeReset()`; add `buildDiscordMessage()` + `formatDuration()` |
| `VoteManager.java` | Add `lastVoterNames` list; expose `getLastVoterNames()`; populate in `evaluateVote()` |
| `ConfigManager.java` | Add `getDiscordStartTemplate()` and `getDiscordCompleteTemplate()` with built-in defaults |
| `HistoryManager.java` | Add `logResetStart()`; colour STARTED entries cyan in `/worldreset history` |
| `config.yml` | Replace `discord-start-message` / `discord-complete-message` with full documented template section |
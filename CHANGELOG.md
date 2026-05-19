# Changelog

All notable changes to WorldResetPlugin are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).  
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.4.6] — Current

### Enhanced
- **Schedule command feedback** (`ResetCommand`) — Added clear warnings when the schedule is disabled, including the exact command to enable it. Improved configuration feedback to show the next fire time immediately after enabling.
- **Schedule state management** (`ScheduleManager`) — Ensured internal state (active mode, firing guards) is correctly reset when the schedule is stopped, providing more accurate status reports.

---

## [1.4.5] — 2026-05-19

### Fixed
- **Difficulty parsing robust and supports numbers** (`ResetManager`) — Difficulty settings from `serverprops` now support both names (`HARD`) and Minecraft's internal IDs (`3`). This ensures that if you set difficulty via the properties editor, it is correctly applied during the reset.
- **Outdated UI warnings** (`ResetCommand`) — Removed the warning that server property patches only apply in restart mode, as they now apply in all modes. Added a clear indicator that patches take effect after the next reset.

---

## [1.4.4] — 2026-05-19

### Fixed
- **Missing `DataVersion` in pre-written `level.dat`** (`ResetManager` — `writeSeedToLevelDat()`) — Modern Paper (1.21+) requires a valid `DataVersion` field in the NBT Data compound; without it, the server would fail to load the world with an `Unknown data version: 0` error. Added `DataVersion: 3953` (1.21) to the pre-written NBT. *(Superseded by the more robust fix in 1.4.3)*.
- **Missing `api-version` in `plugin.yml`** — Added `api-version: '1.21'` to resolve the legacy plugin warning in modern Paper/Spigot versions.
- **Missing seeds in Nether and End worlds after reset** (`ResetManager` — `writeSeedToLevelDat()`) — Nether and End worlds sometimes loaded with a zero seed or failed to allow player joins after a reset because the pre-written `level.dat` was too minimal. The fix adds essential NBT fields (`version`, `LevelName`, `Initialized`) to the pre-written `level.dat` to ensure Paper/Spigot recognizes it as a valid initialized world.
- **Seeds not applied for random-seed worlds in live-regeneration mode** (`ResetManager` — `regenerateWorlds()`) — In live-regeneration mode (`use-restart: false`), random seeds were applied via `WorldCreator.seed()` but the `level.dat` was not being pre-overwritten. On Windows, if the old `level.dat` could not be deleted due to a file lock, Bukkit would reuse the old seed. `writeSeedToLevelDat()` is now called for *every* world in live-regeneration mode (both locked and random) to guarantee the new seed is applied.
- **Difficulty setting ignored or case-sensitive** (`ConfigManager`, `ResetManager`) — Standardized difficulty storage to lowercase and ensured case-insensitive enum lookups when applying difficulty to worlds.

### Changed
- **`hardcore` removed as a synthetic gamerule** (`ResetCommand`) — The redundant `hardcore` intercept in `/worldreset gamerule` and its tab completions have been removed. Use the dedicated `/worldreset hardcore` command instead.
- **Server properties subcommands** (`ResetCommand`) — Added direct subcommands for all 54 standard server properties. You can now run `/worldreset props <key> <value>` (e.g., `/worldreset p pvp false`) directly.
- **Enhanced Discord notifications** (`ResetManager` — `buildDiscordMessage()`) — The `{server_props}` placeholder in Discord messages now automatically includes per-world settings (hardcore, environment, seeds) when they are configured, providing a complete picture of all changes in one list.

---

## [1.4.1] — 2026-05-19

### Fixed
- **Missing seeds in Nether and End worlds after reset** (`ResetManager` — `writeSeedToLevelDat()`) — Nether and End worlds sometimes loaded with a zero seed or failed to allow player joins after a reset because the pre-written `level.dat` was too minimal. The fix adds essential NBT fields (`version`, `LevelName`, `Initialized`) to the pre-written `level.dat` to ensure Paper/Spigot recognizes it as a valid initialized world.
- **Seeds not applied for random-seed worlds in live-regeneration mode** (`ResetManager` — `regenerateWorlds()`) — In live-regeneration mode (`use-restart: false`), random seeds were applied via `WorldCreator.seed()` but the `level.dat` was not being pre-overwritten. On Windows, if the old `level.dat` could not be deleted due to a file lock, Bukkit would reuse the old seed. `writeSeedToLevelDat()` is now called for *every* world in live-regeneration mode (both locked and random) to guarantee the new seed is applied.
- **Difficulty setting ignored or case-sensitive** (`ConfigManager`, `ResetManager`) — Standardized difficulty storage to lowercase and ensured case-insensitive enum lookups when applying difficulty to worlds.

### Changed
- **`hardcore` removed as a synthetic gamerule** (`ResetCommand`) — The redundant `hardcore` intercept in `/worldreset gamerule` and its tab completions have been removed. Use the dedicated `/worldreset hardcore` command instead.
- **Server properties subcommands** (`ResetCommand`) — Added direct subcommands for all 54 standard server properties. You can now run `/worldreset props <key> <value>` (e.g., `/worldreset p pvp false`) directly.
- **Enhanced Discord notifications** (`ResetManager` — `buildDiscordMessage()`) — The `{server_props}` placeholder in Discord messages now automatically includes per-world settings (hardcore, environment, seeds) when they are configured, providing a complete picture of all changes in one list.

---

## [1.4.0] — 2026-05-18

### Added
- **Discord startup notification** (`WorldResetPlugin` — `onEnable()`, `ConfigManager` — `getDiscordStartupTemplate()`, `config.yml` — `notifications.discord-startup-template`) — A Discord webhook message is now sent asynchronously every time `onEnable()` fires, covering both post-reset restarts and normal server starts. The message reports _why_ the server started: `{reason}` is `"World Reset"` when the boot follows a plugin-triggered restart, or `"Normal Start"` otherwise. When the reason is a world reset, `{reset_initiator}` shows the player name, `"Schedule"`, or `"Vote"` that triggered it, and `{reset_time}` shows the timestamp at which the reset began. For normal starts both fields show `"N/A"`. The notification is non-blocking and never delays startup. Default template renders as `🟢 Server Online — {server}` with reason, initiator, and online-at timestamp.

- **Post-restart context persistence** (`ResetManager` — `writePendingRestartFlag()`) — The `pending-post-reset.flag` marker file now stores two `key=value` lines (`initiator=` and `reset_time=`) rather than being an empty file. `WorldResetPlugin.onEnable()` parses these on the next startup so the reason and initiator are available to the Discord startup notification even after the JVM has fully restarted. The file is written via `PrintWriter` with `append=false` (always overwrites) and deleted immediately after being read.

- **`applyPostResetSteps()` public method** (`ResetManager`) — Combines `applyGamerulesAndDifficulty()`, `applyWorldBorder()`, `applySpawn()`, whitelist disable, and post-reset console commands into a single public entry point. Called from `WorldResetPlugin.onEnable()` (post-restart path) and available for external integrations. `applyGamerulesAndDifficulty()`, `applyWorldBorder()`, and `applySpawn()` are also individually promoted from `private` to `public`.

- **Eight new Discord template placeholders** (`ResetManager` — `buildDiscordMessage()`, `config.yml`) — The reset start template now covers every setting that changes on a reset:

  | Placeholder | What it shows |
  |---|---|
  | `{hardcore}` | Per-world hardcore flag (`world: ☠ yes`, `world_nether: no`, …) |
  | `{world_border}` | Enabled/disabled; size and center coordinates when enabled |
  | `{spawn}` | Enabled/disabled; world name and x/y/z when enabled |
  | `{server_props}` | Every `key=value` patch written to `server.properties` on restart |
  | `{pre_commands}` | Commands run before the reset begins |
  | `{post_commands}` | Commands run after the reset finishes |
  | `{preserve_paths}` | Paths that survive the deletion |
  | `{whitelist}` | `"enabled during reset"` or `"not changed"` |

  The existing `discord-start-template` default in `config.yml` is updated to use all new and existing placeholders grouped into sections (worlds, settings applied, data cleared, commands/extras).

### Fixed
- **Gamerules, difficulty, hardcore, world border, and spawn never applied after restart-mode reset** (`ResetManager` — `writePendingRestartFlag()`, `WorldResetPlugin` — `onEnable()`) — In restart mode (`use-restart: true`), `applyGamerulesAndDifficulty()`, `applyWorldBorder()`, and `applySpawn()` were only called in the live-regeneration path. When `spigot().restart()` was issued the JVM exited immediately, and the fresh worlds loaded on the next startup with vanilla defaults regardless of what was configured. The fix writes `pending-post-reset.flag` before calling `spigot().restart()`. On the next `onEnable()`, the flag is detected, deleted, and `applyPostResetSteps()` is scheduled with a 2-tick delay (to ensure all worlds are fully loaded) before being called. The reset-complete broadcast also fires at this point so players joining after the restart see it.

- **Hardcore flag not written to `level.dat` in restart mode** (`ResetManager` — `writeSeedToLevelDat()`) — The minimal gzipped NBT `level.dat` written before `spigot().restart()` previously contained only the seed fields (`RandomSeed` and `WorldGenSettings.seed`). Paper reads the `hardcore` byte from `level.dat` on world creation; since the field was absent it always defaulted to `0` (false), silently ignoring `hardcore.<worldname>` in `config.yml`. The fix adds a `TAG_Byte("hardcore", 1|0)` to the `Data` compound alongside the seed fields and introduces a `writeNbtByte()` helper alongside the existing `writeNbtLong()`. The method signature is updated to `writeSeedToLevelDat(worldName, seed, hardcore)` and all call sites pass the configured hardcore value.

- **Locked seeds ignored in live-regeneration mode when `level.dat` could not be deleted** (`ResetManager` — `regenerateWorlds()`) — `regenerateWorlds()` relied solely on `WorldCreator.seed()` to apply locked seeds. In some Paper/Spigot builds, if a stale `level.dat` survived the deletion phase (file lock held by antivirus or another process), `Bukkit.createWorld()` read the old file and ignored `WorldCreator.seed()` entirely, producing a different seed each reset. The restart path already pre-wrote `level.dat` with `writeSeedToLevelDat()` as the authoritative seed source; the live-regeneration path now does the same — `writeSeedToLevelDat()` is called for every locked-seed world immediately before `Bukkit.createWorld()`. Even if the old file was not fully deleted, the pre-write overwrites it with the correct seed before Paper reads it.

### Changed
- **Discord _complete_ notification removed** (`ResetManager` — `executeReset()`) — The `discord-complete-template` webhook call has been removed from both the restart path (where it was sent synchronously before `spigot().restart()`) and the live-regeneration path. The Discord startup notification (`discord-startup-template`) now serves as the "reset finished, server is back" signal. The `discord-complete-template` key is retained in `config.yml` with a note that it is no longer sent automatically; operators who want a complete message can re-enable it via a custom post-reset command or integration.

- **`hardcore` removed from `/worldreset gamerule`** (`ResetCommand` — `handleGamerule()`) — The FIX-D synthetic `hardcore` row (added in 1.3.2) has been removed from the gamerule list display, the `hardcore` intercept, and the `gamerule` tab-completion suggestions. Typing `/worldreset gamerule hardcore` now shows a one-line redirect message pointing to `/worldreset hardcore`. The help page has been updated to match and now lists `/worldreset hardcore` separately alongside the gamerule section. `hardcore` is a world flag, not a vanilla gamerule, and is already fully managed by the dedicated command added in 1.2.0.

- **`sendDiscordWebhook()` promoted to `public`** (`ResetManager`) — Previously `private`. Made public so `WorldResetPlugin.onEnable()` can send the startup notification without duplicating the HTTP/JSON logic.

- **`discord-start-template` default updated** (`config.yml`) — The built-in default template is rewritten to use all 23 available placeholders, grouped into four labelled sections: _Worlds_, _Settings applied after reset_, _Data cleared_, and _Commands / extras_.

---

## [1.3.2]

### Added
- **`/worldreset props` — direct server.properties editor** (`ResetCommand` — `handleProps()`, `showAllPropsGrouped()`, `showSingleProp()`, `applyPropPatch()`, `printPropRow()`) — A completely new sub-command (aliases: `p`, `prop`) that provides a grouped, browsable view of all 54 standard Minecraft Java Edition server.properties keys, fully separate from `/worldreset serverprops` in UX while sharing the same `ConfigManager` / `ServerPropertiesManager` backend. Running `/worldreset props` with no arguments displays every key organised into nine categories — Gameplay, World Generation, View & Performance, Network, RCON & Query, Access & Ops, Features, Resource Pack, and Modern & Misc — with live values, patch status, and clickable `[Set]` / `[X]` buttons per row. `/worldreset props <key>` opens a detail panel showing the key's description, current value in `server.properties`, the queued patch value (if any), and a list of valid options. `/worldreset props <key> <value>` sets a patch with before→after feedback. `remove`, `enable`, `disable`, and `reload` sub-commands are also supported. Tab-complete at every depth offers all 54 key names plus per-key value hints (boolean, enum, and numeric presets).
- **`PROPS_CATEGORIES`** (`ResetCommand`) — Static `LinkedHashMap<String, List<String>>` mapping nine display-category names to their member keys. Used by `showAllPropsGrouped()` to render the grouped `/worldreset props` output. Every key in `ALL_SERVER_PROP_KEYS` appears exactly once across all categories.
- **`hardcore` as a synthetic gamerule** (`ResetCommand` — `handleGamerule()`) — `/worldreset gamerule hardcore <true|false>` now sets the hardcore world flag for **all** worlds in the reset list via `cfg.setHardcoreForWorld()`. Previously `GameRule.getByName("hardcore")` returned `null` because `hardcore` is a world flag rather than a vanilla gamerule, causing the command to silently do nothing. The fix intercepts the literal string `"hardcore"` before the normal `GameRule` lookup, applies the value to every configured world, and prints per-world confirmation. `/worldreset gamerule remove hardcore` resets all worlds to `false`. The gamerule list display now shows a synthetic `hardcore` row at the top with live per-world state, a colour-coded value indicator, and a clickable `[Edit]` button. Tab-complete at `args.length == 2` for `gamerule` now includes `hardcore` in the suggestion list.
- **Help page 5** (`ResetCommand` — `sendHelp()`) — New help page documents `/worldreset props` with full usage, sub-command list, and the nine key-category breakdown. `HELP_TOTAL_PAGES` updated from 4 to 5. Paginated navigation updated accordingly.

### Fixed
- **`--confirm` flag bypassed the reset safety check** (`ResetCommand` — `handleStart()`) — Running `/worldreset start --confirm` with no prior `/worldreset start` command skipped the confirmation gate entirely and fired the reset immediately. The fix validates that a `pendingConfirm` entry exists for the sender **and** has not expired before allowing `--confirm` to proceed. If no entry is found, the player receives a clear message instructing them to run `/worldreset start` first. The 30-second expiry window and expiry-notification behaviour are unchanged.

### Changed
- **`normalizeSubCmd()`** (`ResetCommand`) — Added `"p"` and `"prop"` → `"props"` alias mappings so tab-complete logic resolves the new sub-command correctly at all argument depths.
- **`onTabComplete()`** (`ResetCommand`) — Extended at `args.length == 2` and `args.length == 3` to cover `props`: length-2 offers all 54 standard key names plus `enable`, `disable`, `reload`, `remove`, `reset`, `clear`; length-3 offers per-key value hints when a standard key is given, or the set of patched keys when `remove` / `reset` / `clear` is given.
- **`/worldreset serverprops`** (`ResetCommand` — `handleServerProps()`) — Footer now includes a tip pointing operators to `/worldreset props` for the grouped view of all 54 keys.

---

## [1.3.1]

### Added
- **Update checker** (`UpdateChecker` — new class, `WorldResetPlugin` — `onEnable()`) — On startup the plugin fires a single async HTTP request to the GitHub Releases API (`GET /repos/{owner}/{repo}/releases/latest`) and compares the returned `tag_name` against the running version from `plugin.yml`. Leading `v` prefixes are stripped before comparison so `"v1.3.1"` and `"1.3.1"` always match correctly. If a newer version is found, a warning is printed to the console and any player with `worldreset.admin` is notified in-game two seconds after joining (delayed so the message is not buried in login noise). The check is fully non-blocking — the main thread is never touched. Network failures produce a single warning log line and are otherwise silent. Configured via two new `config.yml` keys: `update-checker.enabled` (default `true`) and `update-checker.github-repo` (set to `"owner/repo"` of your release repository).
- **`isUpdateCheckerEnabled()` / `getUpdateCheckerRepo()`** (`ConfigManager`) — Two new accessor methods backing the `update-checker` config block. `getUpdateCheckerRepo()` returns an empty string when the key is absent, which `UpdateChecker` treats as "skip and warn" rather than crashing.

### Fixed
- **Discord START notification arrives after COMPLETE** (`ResetManager` — `executeReset()`, line 284) — The START webhook was dispatched with `runTaskAsynchronously`, placing it in a thread-pool queue. COMPLETE fired synchronously on the main thread immediately after and won the race, so Discord showed messages in reverse order. START is now sent synchronously (blocking HTTP). This is acceptable because it is called exactly once, at the moment of reset confirmation, after players have already been kicked.
- **Discord COMPLETE notification never sent in live-regeneration mode** (`ResetManager` — `mainContinue`, line 457) — COMPLETE was re-dispatched with `runTaskAsynchronously` from a task that was already running on the main thread (scheduled back via `runTask`). Async Bukkit tasks can be silently dropped during plugin reloads or server shutdown sequences. COMPLETE is now sent synchronously, matching the behaviour of the restart path.
- **`\n` rendered as literal text in Discord world-detail lines** (`ResetManager` — `buildDiscordMessage()`, line 1134) — `worldsDetail.append("\\n")` appended the two characters `\` and `n`. This embedded the literal string `\n` in the JSON payload, which Discord displayed as plain text rather than a line break. The fix appends `"\n"` (the real newline character); `escapeJson()` then converts it to `\\n` in the JSON body, which Discord correctly interprets as a line break.

---

## [1.3.0]

### Added
- **Rich Discord message templates** (`config.yml` — `notifications.discord-start-template` / `discord-complete-template`) — Both Discord messages are now driven by fully customisable YAML templates. Fifteen placeholders are available: `{server}`, `{initiator}`, `{worlds}`, `{worlds_detail}`, `{mode}`, `{time}`, `{gamerules}`, `{difficulty}`, `{seeds}`, `{extra_paths}`, `{voter_list}`, `{vote_result}`, `{player_data}`, `{backup}`, and `{duration}` (complete message only). Templates can be written as multi-line YAML block scalars (`|`) or single-line strings with `\n`. Edit in `config.yml` and reload with `/worldreset reload` — no server restart required.
- **`buildDiscordMessage()` / `formatDuration()`** (`ResetManager`) — Internal helper that resolves all template placeholders at send-time by reading live config values (gamerules, seeds, player-data toggles, backup settings, voter names, etc.).
- **History `STARTED` entries** (`HistoryManager` — `logResetStart()`) — The history log now records a `STARTED` line the moment a reset is confirmed, completing the full lifecycle trail: `STARTED → RESET` or `STARTED → CANCELLED`. `STARTED` entries are shown in cyan via `/worldreset history`.
- **Voter name tracking** (`VoteManager` — `lastVoterNames` / `getLastVoterNames()`) — `VoteManager` now captures every yes-voter's display name before the vote state is cleared. The list is exposed via `getLastVoterNames()` and consumed by `buildDiscordMessage()` to populate `{voter_list}` and `{vote_result}` in Discord messages.

### Fixed
- **Random-seed worlds always regenerated with the same "default" seed in restart mode** (`ResetManager` — `executeReset()`) — In restart mode (`use-restart: true`), `level.dat` is deleted for every world before `spigot().restart()` is called. The previous code only wrote a new `level.dat` for worlds with a **locked** seed; worlds set to random were left without one. When Paper/Spigot started fresh it fell back to reading `level-seed` from `server.properties`, which still held the seed the server was originally created with — producing the same world on every reset. The fix writes a `level.dat` for **every** world in the reset list: locked worlds receive their stored seed (unchanged, every reset), and random worlds receive a freshly generated `SecureRandom` seed (different on every reset). Each world is resolved independently, so locking or unlocking one world has no effect on the others. This mirrors the behaviour that live-regeneration mode (`use-restart: false`) already had via `WorldCreator.seed()`.
- **Discord complete message never sent in restart mode** (`ResetManager` — `executeReset()`) — The `finally` block in the restart path logged history and cleared `resetPending` but never called the Discord webhook. The complete message is now sent **synchronously** (blocking HTTP) immediately *before* `spigot().restart()` is called. It must be synchronous because async Bukkit tasks are not guaranteed to execute once the JVM begins shutting down.
- **Discord start message fired mid-reset instead of at the start** (`ResetManager` — `asyncWork`) — The start notification was posted inside `asyncWork`, meaning it only reached Discord *after* world folders had already been deleted. It now fires at the very top of `executeReset()`, asynchronously, as the first action when a reset is confirmed.

### Changed
- **`notifications` config section restructured** (`config.yml`) — `discord-start-message` and `discord-complete-message` (plain single-line strings) are superseded by `discord-start-template` and `discord-complete-template`. The old keys are kept as `""` stubs for backwards compatibility; if a template key is absent or empty the plugin falls back to a built-in default that shows all reset details.
- **`/worldreset history` colour scheme** — `STARTED` entries now render in cyan (`§b`). Previously only `RESET` (green) and `CANCELLED` (yellow) had distinct colours; unrecognised lines fell through to grey.

---

## [1.2.2]

### Fixed
- **Locked seeds ignored in restart mode** (`ResetManager` — `writeSeedToLevelDat()`) — In restart mode (`use-restart: true`), `regenerateWorlds()` is never called, so locked seeds configured under `seeds.<worldname>` were silently discarded. After the world folders were deleted, Paper regenerated each world on startup with a fresh random seed because there was no `level.dat` to read. A new `writeSeedToLevelDat()` method now runs on the main thread after deletion but before `spigot().restart()`. It writes a minimal gzipped NBT `level.dat` containing only the seed fields (`RandomSeed` for pre-1.16 Spigot and `WorldGenSettings.seed` for Paper/Spigot 1.16+) for every world that has a locked seed. Paper reads this file on startup and honours the seed. No other world properties (gamemode, difficulty, gamerules, etc.) are written — all other fields remain under Paper's and the plugin's post-reset control as before. This fix uses pure Java `DataOutputStream` with no NMS imports and no version-specific reflection, so it works across all supported Paper/Spigot versions.
- **Silent `IOException` when skipping non-empty directories during deletion** (`ResetManager` — `deleteDirectoryNio()`) — The `postVisitDirectory` handler was catching `IOException` into a blank `ignored` variable with no log output. This violated the project's error-handling rule ("never swallow exceptions silently") and made it impossible to distinguish an expected skip (directory non-empty due to preserved children) from an unexpected deletion failure. The catch block now logs an `info`-level message identifying the directory and the reason it was left on disk.

### Changed
- **`isResetPending()` and `getSecondsLeft()` now have Javadoc** (`ResetManager`) — Both public accessor methods were missing Javadoc, violating the project's requirement that all public methods on manager classes carry a comment explaining purpose and threading context. Both are now documented as main-thread accessors.

### Docs
- **`CONTRIBUTING.md` — No NMS imports rule added** — The existing "No reflection" rule did not explicitly cover direct NMS imports (`net.minecraft.*`, `org.bukkit.craftbukkit.*`). A dedicated rule has been added with rationale (NMS breaks on every major Paper update) and a pointer to `writeSeedToLevelDat()` as the canonical example of handling low-level needs in pure Java.
- **`CONTRIBUTING.md` — Key design rules updated** — Added a bullet documenting the `writeSeedToLevelDat()` mechanism: why it exists, what it writes, and the guarantee that only seed fields are touched.
- **`CONTRIBUTING.md` — Error handling rule tightened** — The rule now explicitly states the two permitted silent catches: `InterruptedException` (must still call `Thread.currentThread().interrupt()`) and documented version-compatibility shims such as `NoSuchMethodError` for pre-1.16 API differences.
- **`CONTRIBUTING.md` — Testing matrix updated** — The single "Locked seed" test row has been split into two: one for live-regeneration mode and one for restart mode. The restart-mode row adds the verification step of confirming only the seed was written to `level.dat` before restart.
- **`CONTRIBUTING.md` — Commit message examples updated** — Added a representative example for the seed fix (`fix(reset): pre-write level.dat with locked seed before restart to ensure Paper honours it`).

---

## [1.2.1]

### Fixed
- **Seed not resetting after reset** (`ResetManager` — `regenerateWorlds()`) — If `level.dat` could not be deleted by the async worker (e.g. a file lock held by antivirus or the JVM on Windows), the previous code called `continue` and skipped `Bukkit.createWorld()` entirely, leaving the world **unloaded** and the server in a broken state. The fix makes one final synchronous deletion attempt on the main thread (all async file handles are released by this point). If `level.dat` still cannot be removed a clear console warning is logged and `createWorld()` is called anyway — the world loads with the old seed for that cycle rather than not loading at all.
- **`[Lock to current]` required a manual Enter press** (`ResetCommand` — `handleSeed()`) — The **[Lock to current]** button used `SUGGEST_COMMAND` (pre-fills the chat bar; the player must press Enter), while the sibling **[Unlock]** button used `RUN_COMMAND` (fires immediately on click). The inconsistency caused players to think locking was broken when they clicked without pressing Enter. **[Lock to current]** now uses `RUN_COMMAND`, matching **[Unlock]**.
- **Seed display lacked context for why all worlds show the same number** (`ResetCommand` — `handleSeed()`) — Spigot creates `world`, `world_nether`, and `world_the_end` with the same seed at first server launch, which made the seed list confusing. A short explanatory note is now shown under the header clarifying that each world has an **independent** seed in Bukkit/Spigot/Paper and can be locked or randomised separately — they only match by default because of how Spigot initialises them.

### Note on nether / end seeds
In **Bukkit / Spigot / Paper** every world folder (`world`, `world_nether`, `world_the_end`) has its own `level.dat` with an independently controllable seed. `WorldCreator.seed()` is fully honoured for all three when `level.dat` has been deleted before `createWorld()` is called. The three worlds show the same seed at first launch only because that is how Spigot creates them; they are not forced to stay in sync. Full lock / unlock controls are therefore shown for every world in the reset list.

---

## [1.2.0]

### Added
- **`advanced.yml`** — New dedicated config file for per-world environment overrides (`world-environments`) and custom generator plugins (`world-generators`). Previously these had to be set by editing internals; they are now fully managed via `/worldreset environment` and `/worldreset generator`.
- **`/worldreset environment`** — Set or clear the Bukkit `World.Environment` (NORMAL / NETHER / THE_END) for any world. Defaults to inferring from the world name suffix if no override is configured.
- **`/worldreset generator`** — Assign a custom generator plugin to a world, or reset it to vanilla.
- **`/worldreset hardcore`** — Toggle permadeath per world. The setting is stored in `config.yml` under `hardcore.<worldname>` and applied via `WorldCreator.hardcore()` on regeneration.
- **`/worldreset seed`** — Interactive seed management with clickable **[Copy]**, **[Lock to current]**, and **[Unlock]** buttons in the chat UI.
- **`/worldreset serverprops`** — Full in-game management of the `server-properties.yml` patch list: set, remove, enable, disable, reload, and compare live values against planned patches.
- **`server-properties.yml`** — Separate config file for the server.properties patch list (previously inline in `config.yml`). Setting a value via `/worldreset serverprops set` auto-enables patching.
- **Cooldown** (`reset.cooldown-seconds`) — Minimum seconds between resets. Players with `worldreset.bypass-cooldown` skip the check.
- **ActionBar countdown** (`reset.actionbar-countdown`, `reset.actionbar-threshold`) — Shows a live ActionBar timer during the final N seconds of the countdown.
- **Discord countdown milestones** (`notifications.discord-countdown-milestones`) — Post a webhook message at specified seconds-remaining intervals (default: 60, 30, 10).
- **Schedule next-fire display** — `/worldreset schedule` now shows when the next reset will fire ("in 4h 23m", "today at 04:00 UTC", etc.).
- **Schedule timezone** (`schedule.timezone`) — IANA zone ID for daily mode. Defaults to `UTC`. Invalid zone IDs fall back to UTC with a console warning.
- **Schedule minimum interval** (`schedule.min-interval-seconds`) — Safety floor to prevent dangerously short auto-reset cycles.
- **Startup backup pruning** — Stale archives accumulated while backups were disabled are pruned automatically on plugin enable.
- **Clickable in-game UI** — `/worldreset worlds`, `/worldreset delete`, `/worldreset preserve`, `/worldreset gamerule`, and `/worldreset serverprops` output now includes clickable **[Add]**, **[Remove]**, **[Edit]**, and **[Unlock]** buttons for players.
- **Paginated help** — `/worldreset help [1-4]` with clickable **[← Prev]** / **[Next →]** navigation.
- **Short command aliases** — `s`, `c`, `st`, `r`/`rl`, `cfg`, `w`, `gr`, `sp`, `sched`, `bk`, `hist`, `env`, `gen` accepted as sub-command aliases.
- **`/worldreset history`** — New `hist` short alias.
- **`pre-reset-commands-strict`** — When `true`, a pre-reset command that returns `false` or throws aborts the entire reset and broadcasts an error.
- **Player data reset** — New `player-data` config section with independent toggles for inventory, ender chest, XP, health, hunger, advancements, and stats.

### Changed
- **`server-properties` config moved** — The `server-properties.values` map has moved from `config.yml` to the new `server-properties.yml`. Existing installs will get the new file created automatically with default values.
- **`/worldreset config`** — Running the sub-command with no extra arguments now prints all current values instead of a usage error.
- **`/worldreset worlds` / `delete` / `preserve` / `gamerule` / `seed` / `schedule` / `backup`** — All now show the relevant list/status when called with no extra arguments ("zero-arg shows status" UX pattern).
- **Reset confirmation** — `/worldreset start` now requires a second invocation (or `--confirm` flag) before firing. The confirmation window expires after 30 seconds with a clear expiry message.
- **Tab completion** — Extended to cover all new sub-commands including `environment`, `generator`, `hardcore`, `serverprops`, and the short aliases.

### Fixed
- **FIX 1 — Async deletion** — NIO file deletion and `deleteWithRetry` now run on an async Bukkit worker thread. `createWorld()` and `applyGamerulesAndDifficulty()` are scheduled back on the main thread via `Bukkit.getScheduler().runTask()`. Controlled by `async.delete-async` (default `true`).
- **FIX 2 — Paper world registry** — Removed a reflection hack that cleared the internal Paper world registry. `Bukkit.unloadWorld()` already updates the registry; no reflection is needed.
- **FIX 3 — Type-safe gamerule application** — Replaced the deprecated `World.setGameRuleValue(String, String)` with the type-safe `World.setGameRule(GameRule<T>, T)`. Boolean and Integer gamerule types are handled; unknown names and bad values produce a clear log warning instead of silently failing.
- **FIX 4 — Reliable server root** — `serverRoot()` now uses `new File(".").getCanonicalFile()` (the JVM working directory) as the primary method, with `Bukkit.getWorldContainer().getParentFile()` as a fallback. The old `getDataFolder().getParentFile().getParentFile()` chain was fragile when the server was launched from a different working directory.
- **FIX 5 — `resetPending` guard in restart path** — `resetPending` is now cleared in a `finally` block around `spigot().restart()` so it cannot be stuck as `true` if the restart call throws an exception.
- **FIX 6 — `api-version`** — `plugin.yml` updated to `api-version: 1.20`.
- **FIX 7 — Confirmation step** — Implemented in `ResetCommand` (see above).
- **FIX 8 — Reload guard** — `/worldreset reload` now refuses to run while a reset countdown is in progress, preventing a race where the config changes mid-reset.
- **Seed UI bug** — The **[Unlock]** button was incorrectly shown in the "random seed" branch of `/worldreset seed`. It now appears only when a seed is actually locked. The "random" branch shows only **[Lock to current]**. *(Further seed UI corrections and nether/end seed handling are in 1.2.1.)*

---

## [1.1.0]

### Added
- **Backup system** — ZIP-based world backups before deletion, with configurable archive count and directory.
- **Schedule manager** — Interval and daily auto-reset modes.
- **Community vote-to-reset** — `/resetvote` command with configurable percent threshold, duration, and minimum player count.
- **Discord webhook notifications** — Reset start and complete messages via HTTP POST.
- **History log** — Persistent `reset-history.log` with coloured in-game viewer.
- **World border reset** — Configurable size and centre applied after regeneration.
- **Spawn reset** — Configurable spawn location applied after regeneration.
- **Whitelist lock** — Auto-enable whitelist during reset; auto-disable after.
- **Pre/post-reset commands** — Run arbitrary console commands before and after the reset.
- **Multiple worlds** — Any number of worlds can be listed in `worlds:`.
- **Preserve paths** — Files and directories that should never be deleted even if they fall under a delete path.
- **Locked seeds** — Per-world seed pinning via `seeds.<worldname>` in `config.yml`.

### Fixed
- World folder deletion now uses NIO `walkFileTree` with a retry loop instead of recursive `File.delete()`, significantly improving reliability on Windows.
- `Bukkit.unloadWorld()` now passes `save=false` to avoid redundant disk writes before deletion.

---

## [1.0.0] — Initial Release

- Single-world reset with configurable countdown.
- Title, subtitle, and chat broadcast during countdown.
- Player kick on reset start.
- World unload → folder delete → `Bukkit.createWorld()` regeneration.
- Gamerule and difficulty application after regeneration.
- Basic `config.yml` with messages, countdown, worlds, and gamerules sections.
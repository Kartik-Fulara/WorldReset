# Contributing to WorldResetPlugin

Thank you for taking the time to contribute. This document explains how to report bugs, suggest features, and submit pull requests.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How to Report a Bug](#how-to-report-a-bug)
- [How to Request a Feature](#how-to-request-a-feature)
- [Development Setup](#development-setup)
- [Project Structure](#project-structure)
- [Versioning](#versioning)
- [Git Hooks](#git-hooks)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Coding Standards](#coding-standards)
- [Testing](#testing)
- [Commit Messages](#commit-messages)

---

## Code of Conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). By participating you agree to abide by its terms.

---

## How to Report a Bug

1. **Search existing issues** first — the bug may already be tracked.
2. Open a new issue and include:
   - Plugin version (`/worldreset status` → top line).
   - Server software and version (Paper 1.21.x, Spigot 1.20.x, etc.).
   - Java version (`java -version`).
   - A clear description of what you expected vs. what happened.
   - The relevant section of your `config.yml` (remove any sensitive values like webhook URLs).
   - The full server log from the moment the reset fired until the error (use a paste service such as [mclo.gs](https://mclo.gs)).
3. Label the issue **bug**.

---

## How to Request a Feature

1. **Search existing issues** — there may be an open request or a reason it was declined.
2. Open a new issue labelled **enhancement** and describe:
   - The problem you are trying to solve.
   - Your proposed solution and any alternatives you considered.
   - Any configuration keys, commands, or permissions your feature would add.

---

## Development Setup

**Prerequisites**

- Java 17 or newer (required — matches the minimum Paper version this plugin targets).
- Maven 3.8+.
- A local Paper 1.20+ test server.

**Steps**

```bash
git clone https://github.com/<your-org>/WorldResetPlugin.git
cd WorldResetPlugin
mvn package -DskipTests
```

Then install the git hooks (required — enforces commit format and version consistency):

**Linux / macOS**
```bash
chmod +x install-hooks.sh && ./install-hooks.sh
```

**Windows** — open **Git Bash** (right-click the project folder → *Git Bash Here*), then:
```bash
bash install-hooks.sh
```

The script detects your platform automatically. The hooks are bash scripts; on Windows they run via the bash shell bundled with [Git for Windows](https://git-scm.com/download/win) so no extra setup is needed for normal git operations (`git commit`, `git push`, etc.).

The compiled JAR is placed in `target/`. Copy it to your test server's `plugins/` folder.

**Reload without restarting** (during development):
```
/worldreset reload
```

---

## Project Structure

```
WorldResetPlugin/
├── VERSION                                # Single source of truth for the release version
├── CHANGELOG.md
├── install-hooks.sh                       # Hook installer — Linux, macOS, and Windows (via Git Bash)
├── pom.xml
├── .githooks/
│   ├── pre-commit                         # Checks version consistency + staged file quality
│   ├── commit-msg                         # Enforces Conventional Commits prefix format
│   └── pre-push                           # Guards version bump, build compile, and semver format
├── .github/
│   └── workflows/
│       └── release.yml                    # Builds JAR and publishes GitHub Release when VERSION changes
├── backups/                               # Runtime — generated on reset; gitignored
└── src/main/
    ├── java/com/worldreset/
    │   ├── WorldResetPlugin.java          # Plugin entry point; wires all managers together
    │   ├── commands/
    │   │   └── ResetCommand.java          # /worldreset sub-command dispatcher + tab completion
    │   └── managers/
    │       ├── ConfigManager.java         # Thin wrapper around config.yml / server-properties.yml / advanced.yml
    │       ├── HistoryManager.java        # Reads and writes reset-history.log
    │       ├── ResetManager.java          # Full reset lifecycle (countdown → delete → regenerate)
    │       ├── ScheduleManager.java       # Interval and daily auto-reset schedule
    │       ├── ServerPropertiesManager.java  # Reads, caches, and patches server.properties
    │       └── VoteManager.java           # /resetvote community vote logic
    └── resources/
        ├── config.yml                     # Default configuration
        ├── plugin.yml                     # Commands, permissions, metadata
        ├── server-properties.yml          # Default server.properties patch list
        └── advanced.yml                   # Default world environments / generators template
```

### Key design rules

- **`ResetManager` owns the reset lifecycle.** No other class calls `Bukkit.unloadWorld()`, `Bukkit.createWorld()`, or file deletion.
- **Threading contract:** Everything before the async block runs on the **main thread**. File I/O (backup + deletion) runs on an **async worker**. Everything from `createWorld()` onward runs back on the **main thread** via `Bukkit.getScheduler().runTask()`.
- **`ConfigManager` is the single source of truth for config values.** Read from config through it; never call `plugin.getConfig()` directly outside `ConfigManager`.
- **`ServerPropertiesManager` owns `server.properties`.** Do not read or write that file anywhere else.
- **All worlds get a `level.dat` written before restart.** In restart mode (`use-restart: true`), `Bukkit.createWorld()` is never called, so seeds cannot be applied through `WorldCreator`. `ResetManager.writeSeedToLevelDat()` runs for **every** world in the reset list before `spigot().restart()` is called — locked worlds receive their stored seed so it remains constant across every reset, and random worlds receive a freshly generated `SecureRandom` seed so each reset produces a genuinely different world. Without this, Paper falls back to reading `level-seed` from `server.properties`, which holds the server's original creation seed and never changes on its own. **Only seed fields are written** (`RandomSeed` for pre-1.16, `WorldGenSettings.seed` for 1.16+) — all other world properties are intentionally omitted so Paper applies its own defaults and the plugin's post-reset steps remain in full control. Each world is resolved independently; locking or unlocking one world has no effect on any other.

---

## Versioning

The project uses a single `VERSION` file in the repo root as the **authoritative version number**. Everything else derives from it.

```
1.3.0
```

**The VERSION file drives the entire release pipeline:**

| File | How it is kept in sync |
|---|---|
| `VERSION` | Edited by hand when starting a new release |
| `pom.xml` | Updated by `mvn versions:set` — run manually or by the release workflow |
| `src/main/resources/plugin.yml` | Updated by `sed` — run manually or by the release workflow |
| `CHANGELOG.md` | Edited by hand; must contain a `## [VERSION]` block matching `VERSION` |

**Release flow:**

1. Finish your changes on a feature branch.
2. Update `CHANGELOG.md` — add a `## [x.y.z]` block with the new version's notes.
3. Update `VERSION` to match: `echo "x.y.z" > VERSION`.
4. Sync `pom.xml`: `mvn versions:set -DnewVersion=x.y.z -DgenerateBackupPoms=false`.
5. Sync `plugin.yml`: `sed -i "s/^version:.*/version: 'x.y.z'/" src/main/resources/plugin.yml`.
6. Stage all four files and commit: `git add VERSION CHANGELOG.md pom.xml src/main/resources/plugin.yml`.
7. Push to `main`. The release workflow detects the new `VERSION`, builds the JAR, and publishes a GitHub Release automatically.

The pre-commit and pre-push hooks enforce that `VERSION`, `pom.xml`, and `plugin.yml` all agree before a commit or push is allowed through.

---

## Git Hooks

All hooks live in `.githooks/` and are installed once per clone by running `install-hooks.sh`. They are **required** — PRs that bypass them will be asked to correct the affected commits before merging.

| Hook | When it runs | What it checks |
|---|---|---|
| `pre-commit` | Before the commit message editor opens | Merge-conflict markers; debug `println` / `printStackTrace` in staged Java files; FIXME/HACK warnings; VERSION, pom.xml, and plugin.yml version consistency; CHANGELOG.md staged without VERSION |
| `commit-msg` | After you type the commit message | Conventional Commits format (`type(scope): description`); subject line ≤ 100 chars; WIP commits blocked; breaking-change reminder |
| `pre-push` | Before data is sent to the remote | VERSION is valid semver; no `-SNAPSHOT` on `main`; VERSION changed vs `origin/main`; VERSION matches top CHANGELOG.md entry; pom.xml and plugin.yml versions match; `mvn compile` passes |

**Installing:**

Linux / macOS:
```bash
chmod +x install-hooks.sh && ./install-hooks.sh
```

Windows — open **Git Bash** (right-click the project folder → *Git Bash Here*), then:
```bash
bash install-hooks.sh
```

Any platform — one-liner alternative (no script needed):
```bash
git config core.hooksPath .githooks
```

> **Windows note:** do not run the installer from PowerShell or CMD — use Git Bash.
> Once installed, hooks fire automatically during `git commit` and `git push` via the
> bash shell bundled with [Git for Windows](https://git-scm.com/download/win); no further
> configuration is needed.

**Bypassing (emergency only):**

```bash
git commit --no-verify   # skips pre-commit + commit-msg
git push   --no-verify   # skips pre-push
```

Use `--no-verify` only when you have a clear reason (e.g. fixing a broken `main` quickly). Document why in the PR description.

---

## Submitting a Pull Request

1. **Fork** the repository and create a feature branch from `main`:
   ```bash
   git checkout -b feature/my-feature
   ```
2. Install the git hooks if you have not already (see [Git Hooks](#git-hooks)).
3. Make your changes following the [coding standards](#coding-standards) below.
4. Test your changes on a local Paper server (see [Testing](#testing)).
5. Update `CHANGELOG.md` — add your changes under the current `## [x.y.z]` block. If this PR warrants a version bump, follow the [Versioning](#versioning) steps to update `VERSION`, `pom.xml`, and `plugin.yml` as well.
6. If you are adding a new config key, add it to `config.yml` with a comment and document it in `README.md`.
7. If you are adding a new command or permission, update `plugin.yml` and the Commands/Permissions tables in `README.md`.
8. **Push** your branch and open a pull request against `main`.
9. Fill in the PR template: what changed, why, and how you tested it.

PRs that break the threading contract, remove existing config keys without a migration path, change command syntax without updating the help text, or have mismatched `VERSION`/`pom.xml`/`plugin.yml` versions will be asked to revise before merging.

---

## Coding Standards

- **Java style** — 4-space indentation, no tabs. Opening braces on the same line (`K&R` style).
- **Javadoc** — public methods on `ResetManager`, `ConfigManager`, `HistoryManager`, `ScheduleManager`, `ServerPropertiesManager`, and `VoteManager` must have a Javadoc comment explaining the purpose, threading context (main vs. async), and any non-obvious behaviour.
- **No reflection** — Do not use reflection to access Bukkit/Paper internals. Use the public API. If the API does not support what you need, open an issue to discuss the approach first.
- **No NMS imports** — Do not import `net.minecraft.*` or `org.bukkit.craftbukkit.*` classes. These are version-specific and break on every major Paper update. If low-level access is genuinely required (e.g. writing binary file formats), implement it in pure Java against the relevant open specification — see `writeSeedToLevelDat()` in `ResetManager` as an example of writing NBT without NMS.
- **No deprecated Bukkit APIs** — Use the type-safe `GameRule<T>` API, `Bukkit.unloadWorld()`, and `WorldCreator` builder methods. Do not call `World.setGameRuleValue(String, String)`.
- **Logging** — Use `plugin.getLogger()`. Use `log.info()` for normal events, `log.warning()` for recoverable issues, and `log.severe()` for failures that may leave the server in a bad state.
- **Config access** — All config reads go through `ConfigManager`. Do not add raw `plugin.getConfig().getString(...)` calls outside that class.
- **Thread safety** — Any code that touches Bukkit's world registry, players, or entities must run on the main thread. File I/O should run on an async thread. Document the expected thread in Javadoc when it is not obvious.
- **Error handling** — Catch `IOException` around all file operations and log a meaningful message. Never swallow exceptions silently. The only permitted silent catches are `InterruptedException` (which must still call `Thread.currentThread().interrupt()`) and documented version-compatibility shims such as `NoSuchMethodError` for pre-1.16 API differences.

---

## Testing

There is currently no automated test suite. Manual testing is required before submitting a PR.

**Minimum test matrix:**

| Scenario | Steps |
|---|---|
| Live regeneration | Set `use-restart: false`. Run `/worldreset start --now --confirm`. Verify worlds regenerate with correct seeds/gamerules. |
| Restart mode | Set `use-restart: true`. Run `/worldreset start --now --confirm`. Verify server restarts cleanly and server.properties is patched. |
| Locked seed — live regeneration | Set `use-restart: false`. Lock a seed with `/worldreset seed world <number>`. Reset. Confirm `Actual seed` in the log matches the locked value. |
| Locked seed — restart mode | Set `use-restart: true`. Lock a seed with `/worldreset seed world <number>`. Reset. After the server comes back up, confirm the world seed matches the locked value (check `level.dat` or run `/worldreset status`). Verify that **only** the seed was written to `level.dat` before restart — no other world properties should be pre-set. |
| Backup | Enable backup. Reset. Confirm a ZIP appears in `backups/` and old archives are pruned. |
| Schedule (interval) | Set a short interval (e.g. 120s). Wait. Confirm reset fires automatically. |
| Schedule (daily) | Set `daily-time` to 2 minutes in the future. Wait. Confirm reset fires once. |
| Vote | Enable vote. Run `/resetvote` with enough players. Have them run `/resetvote yes`. Confirm reset triggers on pass or does not trigger on fail. |
| Preserve paths | Add a file path to `extra-paths.preserve`. Reset. Confirm the file is not deleted. |
| Reload guard | Start a countdown. Try `/worldreset reload`. Confirm it is refused. Cancel. Reload. Confirm it succeeds. |
| Cooldown | Complete a reset. Immediately try another. Confirm it is blocked. Wait for the cooldown. Confirm it succeeds. |

---

## Commit Messages

Use the [Conventional Commits](https://www.conventionalcommits.org/) format:

```
<type>(<scope>): <short summary>

[optional body]

[optional footer: Closes #123]
```

**Types:**

| Type | Use for |
|---|---|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `style` | Formatting, no logic change |
| `refactor` | Code restructure, no feature or fix |
| `perf` | Performance improvement |
| `test` | Add or correct tests |
| `build` | Build system or dependency changes (Maven, pom.xml) |
| `ci` | CI configuration (.github/workflows, hooks) |
| `chore` | Maintenance tasks that don't touch src or tests |
| `revert` | Revert a previous commit |

**Examples:**
```
feat(vote): add min-players check before opening vote window
fix(reset): clear resetPending in finally block around spigot().restart()
fix(reset): pre-write level.dat with locked seed before restart to ensure Paper honours it
docs(readme): document server-properties.yml migration from 1.1.0
chore(deps): update Paper API to 1.20.4
ci(hooks): add pre-push semver guard for main branch
```
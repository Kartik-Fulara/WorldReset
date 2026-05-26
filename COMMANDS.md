# Command Reference

All commands start with `/worldreset` (aliases: `/wr`, `/wrs`, `/wreset`).

## Core Commands
| Command | Description |
|:--- |:--- |
| `/worldreset start` | Initiate a world reset (requires confirmation). |
| `/worldreset start --now` | Skip the countdown and trigger reset immediately. |
| `/worldreset cancel` | Abort a pending reset. |
| `/worldreset menu` | Open the interactive GUI Dashboard. |
| `/worldreset status` | View current reset state, worlds, and settings. |
| `/worldreset reload` | Reload all configurations from disk. |
| `/worldreset history [n]` | View the last `n` resets in the history log. |

## Configuration Settings
Use `/worldreset config <key> <value>` to update settings.

| Key | Values | Description |
|:--- |:--- |:--- |
| `countdown` | `number` | Seconds before reset starts. |
| `interval` | `number` | Seconds between countdown broadcasts. |
| `kick-msg` | `text` | Message shown to players on kick. |
| `difficulty` | `PEACEFUL/EASY/NORMAL/HARD` | World difficulty after reset. |
| `use-restart` | `true/false` | Restart server vs live regeneration. |
| `delete-async` | `true/false` | Delete world files off-thread. |
| `whitelist-during-reset` | `true/false` | Block joins while reset is active. |

## World & Seed Management
| Command | Description |
|:--- |:--- |
| `/worldreset worlds [add/remove] <name>` | Manage the list of worlds to reset. |
| `/worldreset seed <world> <number>` | Lock a world to a specific seed. |
| `/worldreset seed <world> random` | Use a new random seed for every reset. |
| `/worldreset hardcore <world>` | Toggle hardcore mode for a world. |
| `/worldreset environment <world> <env>` | Override world environment (NORMAL/NETHER/THE_END). |
| `/worldreset generator <world> <gen>` | Set a custom generator plugin. |

## Schedule & Backup
| Command | Description |
|:--- |:--- |
| `/worldreset schedule enable/disable` | Toggle the auto-reset schedule. |
| `/worldreset schedule mode <interval/daily>` | Switch schedule logic. |
| `/worldreset backup now` | Immediately trigger an async backup. |
| `/worldreset backup list` | List available backup ZIP archives. |
| `/worldreset backup restore <zip>` | Restore worlds from a ZIP archive (destructive). |
| `/worldreset backup keep <n>` | Set how many old backups to retain. |
| `/worldreset backup dir <path>` | Set the backup directory path. |
| `/worldreset backup enable/disable` | Toggle automatic backups before resets. |

## Server Properties (Advanced)
| Command | Description |
|:--- |:--- |
| `/worldreset props` | Opens the grouped server.properties editor. |
| `/worldreset props <key> <value>` | Set a patch for any of the 54 standard keys. |
| `/worldreset props remove <key>` | Remove a property patch. |

## Community Voting
| Command | Description |
|:--- |:--- |
| `/resetvote` | Start a community reset vote. |
| `/resetvote yes` | Vote yes in an active window. |

---
**Permissions:**
- `worldreset.admin`: Full access (Default: OP)
- `worldreset.start`: Access to start resets
- `worldreset.vote`: Access to participate in votes (Default: true)

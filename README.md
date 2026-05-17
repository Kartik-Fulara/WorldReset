# 🌍 WorldResetPlugin

![Version](https://img.shields.io/badge/version-1.2.0-blue.svg)
![API](https://img.shields.io/badge/API-1.20+-success.svg)
![Server](https://img.shields.io/badge/Server-Paper%20/%20Spigot-orange.svg)

A full-featured Minecraft world-reset plugin for Paper and Spigot servers. Reset one or more worlds on demand or on a schedule—complete with countdown broadcasts, backups, Discord notifications, community vote-to-reset, and safe async file operations.

---

## 📑 Table of Contents

- [✨ Features](#-features)
- [🚀 Installation](#-installation)
- [⚡ Quick Start](#-quick-start)
- [💻 Commands](#-commands)
- [🔑 Permissions](#-permissions)
- [⚙️ Configuration](#️-configuration)
- [🔄 Reset Lifecycle](#-reset-lifecycle)
- [🛠️ Troubleshooting](#️-troubleshooting)

---

## ✨ Features

### Core Operations
* **Multi-world Reset:** Reset any number of worlds in one single operation.
* **Two Reset Modes:** Choose between **Live regeneration** (no restart) or **Server restart**.
* **Async File Deletion:** World folders are deleted off the main thread to prevent server freezes.
* **Automatic Backups:** ZIP each world before deletion; automatically prune old archives.
* **Server Properties Patching:** Overwrite any key in `server.properties` upon restart.

### Player & Community
* **Interactive Countdown:** Title, subtitle, chat, and ActionBar timers to warn players.
* **Community Vote:** Players can use `/resetvote yes` to trigger a reset if the threshold is met.
* **Discord Notifications:** Webhooks notify your server on reset start, complete, and countdown milestones.
* **Player Data Reset:** Option to clear inventory, XP, health, advancements, and stats.

### World Customization
* **Locked Seeds:** Pin a world to a fixed seed across every reset.
* **Hardcore Mode:** Toggle permadeath per world after a reset.
* **Custom Environments & Generators:** Override `NORMAL` / `NETHER` / `THE_END` and use custom generator plugins.
* **Gamerules & Borders:** Type-safe gamerule, difficulty, world border, and spawn point application post-reset.

---

## 🚀 Installation

1. Download the latest `WorldResetPlugin-<version>.jar`.
2. Place it in your server's `plugins/` folder.
3. Restart or reload the server.
4. Edit `plugins/WorldResetPlugin/config.yml` to suit your server.
5. Run `/worldreset reload` to apply changes without a full restart.

> **Note:** Requires Java 11+ and Paper or Spigot 1.20+

---

## ⚡ Quick Start

```bash
# Start a reset with a confirmation prompt
/worldreset start

# Start immediately, no countdown, no second prompt
/worldreset start --now --confirm

# Cancel a running countdown
/worldreset cancel

# Check all settings at a glance
/worldreset status
#!/usr/bin/env bash
# =============================================================================
# install-hooks.sh  —  cross-platform git hook installer
#
# Works on Linux, macOS, and Windows.
#
#   Linux / macOS
#     chmod +x install-hooks.sh && ./install-hooks.sh
#
#   Windows — open "Git Bash" (ships with Git for Windows), then run:
#     bash install-hooks.sh
#
#   Any platform — alternative one-liner (no script needed):
#     git config core.hooksPath .githooks
#
# Requires Git 2.9+ (released 2016).
# =============================================================================

set -euo pipefail

RED='\033[0;31m'
GRN='\033[0;32m'
YEL='\033[0;33m'
BLU='\033[0;34m'
NC='\033[0m'

ok()   { echo -e "  ${GRN}✔${NC} $*"; }
warn() { echo -e "  ${YEL}⚠${NC} $*"; }
fail() { echo -e "  ${RED}✖${NC} $*"; exit 1; }
info() { echo -e "  ${BLU}▸${NC} $*"; }

# ── Detect platform ───────────────────────────────────────────────────────────
case "$(uname -s 2>/dev/null)" in
  MINGW* | CYGWIN* | MSYS*)  PLATFORM="windows" ;;
  Darwin*)                    PLATFORM="macos"   ;;
  *)                          PLATFORM="linux"   ;;
esac

echo ""
echo -e "${BLU}  Installing git hooks for WorldResetPlugin${NC}  ${YEL}[$PLATFORM]${NC}"
echo ""

# ── 1. Confirm we are inside a git repository ─────────────────────────────────
info "Checking git repository…"
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
[[ -z "$REPO_ROOT" ]] && fail "Not inside a git repository — run this from the project root."
ok "Repository root: $REPO_ROOT"

# ── 2. Confirm .githooks/ exists ──────────────────────────────────────────────
HOOKS_DIR="$REPO_ROOT/.githooks"
[[ ! -d "$HOOKS_DIR" ]] && fail ".githooks/ directory not found at: $HOOKS_DIR"

# ── 3. Set core.hooksPath ─────────────────────────────────────────────────────
info "Setting core.hooksPath to .githooks/ …"
git config core.hooksPath .githooks
ok "core.hooksPath → .githooks"

# ── 4. Ensure hook files are executable ───────────────────────────────────────
#   On Linux/macOS this sets the filesystem execute bit.
#   On Windows (Git Bash / MINGW) chmod updates the executable bit in the git
#   index — Git for Windows reads this bit when deciding whether to run a hook.
info "Marking hook files executable…"
count=0
for hook in "$HOOKS_DIR"/*; do
  [[ -f "$hook" ]] || continue
  chmod +x "$hook"
  ok "$(basename "$hook")"
  count=$(( count + 1 ))
done

# ── 5. Windows-specific notice ────────────────────────────────────────────────
if [[ "$PLATFORM" == "windows" ]]; then
  echo ""
  warn "Windows detected — a couple of things to be aware of:"
  echo "       • Always use Git Bash (not PowerShell or CMD) to run bash scripts."
  echo "       • Hooks run automatically via the bash shell bundled with Git for"
  echo "         Windows — no extra setup needed for 'git commit', 'git push', etc."
fi

# ── 6. Done ───────────────────────────────────────────────────────────────────
echo ""
echo -e "  ${GRN}Done.${NC} $count hook(s) active — they will run automatically on git operations."
echo ""
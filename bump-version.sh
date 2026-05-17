#!/usr/bin/env bash
# =============================================================================
# bump-version.sh
#
# Bumps the version across VERSION, pom.xml, and plugin.yml.
# Fully platform-aware: Linux, macOS, WSL, Git Bash, Cygwin.
#
# Usage:
#   bash bump-version.sh <new-version>
#   bash bump-version.sh 1.3.0
# =============================================================================

set -uo pipefail

RED='\033[0;31m'
GRN='\033[0;32m'
YEL='\033[0;33m'
BLU='\033[0;34m'
NC='\033[0m'

ok()   { echo -e "  ${GRN}✔${NC} $*"; }
fail() { echo -e "  ${RED}✖${NC} $*"; }
warn() { echo -e "  ${YEL}⚠${NC} $*"; }
info() { echo -e "  ${BLU}▸${NC} $*"; }

# =============================================================================
# 0. Detect environment
# =============================================================================
# PLATFORM  : linux | macos | wsl | gitbash | cygwin
# WIN_PATHS : true if Java/Maven live under Windows-style roots (/c/... or /mnt/c/...)
case "$(uname -s)" in
  Darwin*)             PLATFORM="macos"   ; WIN_PATHS=false ;;
  MINGW* | MSYS*)      PLATFORM="gitbash" ; WIN_PATHS=true  ;;
  CYGWIN*)             PLATFORM="cygwin"  ; WIN_PATHS=true  ;;
  Linux*)
    if grep -qi microsoft /proc/version 2>/dev/null; then
      PLATFORM="wsl"   ; WIN_PATHS=true   # WSL can see Windows drives at /mnt/c
    else
      PLATFORM="linux" ; WIN_PATHS=false
    fi
    ;;
  *)                   PLATFORM="linux"   ; WIN_PATHS=false ;;
esac

# ── Argument check ────────────────────────────────────────────────────────────
if [[ -z "${1-}" ]]; then
  fail "No version provided."
  echo "  Usage:   bash bump-version.sh <new-version>"
  echo "  Example: bash bump-version.sh 1.3.0"
  exit 1
fi

NEW_VERSION="$1"

if ! [[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9.]+)?$ ]]; then
  fail "'$NEW_VERSION' is not a valid semver string (expected e.g. 1.3.0 or 1.3.0-SNAPSHOT)."
  exit 1
fi

echo ""
echo -e "${BLU}  Bumping project version to $NEW_VERSION${NC}  ${YEL}[$PLATFORM]${NC}"
echo ""

errors=0

# =============================================================================
# Helper — locate an executable, checking PATH then platform-specific roots
# =============================================================================
# Usage: find_cmd <varname> <binary-name> [extra search dirs...]
find_cmd() {
  local var="$1" bin="$2"; shift 2
  local found=""

  # 1. PATH first
  found=$(command -v "$bin" 2>/dev/null || true)

  # 2. Caller-supplied extra dirs
  if [[ -z "$found" ]]; then
    local dir
    for dir in "$@"; do
      if [[ -x "$dir/$bin" ]]; then found="$dir/$bin"; break; fi
    done
  fi

  printf -v "$var" '%s' "$found"
}

# =============================================================================
# Helper — resolve JAVA_HOME from a java binary path, handling each platform
# =============================================================================
resolve_java_home() {
  local java_bin="$1"

  case "$PLATFORM" in
    macos)
      # macOS ships a shim at /usr/bin/java; the real JDK is found via java_home
      if [[ -x "/usr/libexec/java_home" ]]; then
        /usr/libexec/java_home 2>/dev/null && return
      fi
      # Fall through to symlink resolution
      ;;
    gitbash | cygwin)
      # readlink -f doesn't work on Windows paths in Git Bash/Cygwin.
      # Use `java -XshowSettings:all` output instead.
      java -XshowSettings:all -version 2>&1 \
        | grep 'java.home' \
        | sed 's/.*java.home = //' \
        | sed 's|\\|/|g'  # normalise backslashes
      return
      ;;
  esac

  # Linux / WSL — readlink works fine
  local real
  real=$(readlink -f "$java_bin" 2>/dev/null || echo "$java_bin")
  dirname "$(dirname "$real")"
}

# =============================================================================
# Helper — platform-aware sed -i
# =============================================================================
portable_sed_i() {
  local pattern="$1" file="$2"
  case "$PLATFORM" in
    macos) sed -i ''  "$pattern" "$file" ;;
    *)     sed -i     "$pattern" "$file" ;;
  esac
}

# =============================================================================
# 1. VERSION file
# =============================================================================
info "Updating VERSION…"
echo "$NEW_VERSION" > VERSION
ok "VERSION → $NEW_VERSION"

# =============================================================================
# 2. pom.xml via Maven
# =============================================================================
if [[ -f "pom.xml" ]]; then
  info "Updating pom.xml…"

  # ── 2a. Locate mvn ──────────────────────────────────────────────────────────
  MVN_CMD=""

  case "$PLATFORM" in
    wsl)
      # Ask Windows where mvn actually is, then convert to a WSL path.
      WIN_MVN=$(powershell.exe -NoProfile -Command         "(Get-Command mvn -ErrorAction SilentlyContinue).Source"         2>/dev/null | tr -d '\r\n')
      if [[ -n "$WIN_MVN" ]]; then
        MVN_CMD=$(wslpath "$WIN_MVN" 2>/dev/null || true)
      fi
      # Fallback: mvn may also be installed natively inside WSL
      [[ -z "$MVN_CMD" ]] && MVN_CMD=$(command -v mvn 2>/dev/null || true)
      ;;
    gitbash)
      # Ask Windows via where.exe, then normalise the path for Git Bash.
      WIN_MVN=$(cmd.exe /c "where mvn" 2>/dev/null | head -1 | tr -d '\r\n')
      if [[ -n "$WIN_MVN" ]]; then
        MVN_CMD=$(echo "$WIN_MVN" | sed 's|\\|/|g; s|^\([A-Za-z]\):|/\L\1|')
      fi
      [[ -z "$MVN_CMD" ]] && MVN_CMD=$(command -v mvn 2>/dev/null || true)
      ;;
    macos)
      find_cmd MVN_CMD mvn \
        "/opt/homebrew/bin" \
        "/usr/local/bin" \
        "$HOME/bin"
      ;;
    *)
      find_cmd MVN_CMD mvn "$HOME/bin"
      ;;
  esac

  if [[ -z "$MVN_CMD" ]]; then
    fail "Maven not found — skipping pom.xml."
    warn "Install Maven and ensure 'mvn' is on your PATH, then run:"
    warn "  mvn versions:set -DnewVersion=$NEW_VERSION -DgenerateBackupPoms=false"
    errors=$(( errors + 1 ))
  else
    # ── 2b. Resolve JAVA_HOME if unset ────────────────────────────────────────
    if [[ -z "${JAVA_HOME-}" ]]; then

      case "$PLATFORM" in

        wsl)
          # Ask Windows directly via PowerShell — converts the result to a WSL
          # path with wslpath so Maven (running under WSL) can use it.
          WIN_JAVA=$(powershell.exe -NoProfile -Command "
            @(
              [System.Environment]::GetEnvironmentVariable('JAVA_HOME','Machine'),
              [System.Environment]::GetEnvironmentVariable('JAVA_HOME','User'),
              (Get-Command java -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty Source)
            ) | Where-Object { \$_ } | Select-Object -First 1
          " 2>/dev/null | tr -d '\r\n')

          if [[ -n "$WIN_JAVA" ]]; then
            # If we got the java.exe path, walk up to JAVA_HOME
            if [[ "$WIN_JAVA" == *.exe ]]; then
              WIN_JAVA=$(powershell.exe -NoProfile -Command                 "Split-Path (Split-Path '$WIN_JAVA' -Parent) -Parent"                 2>/dev/null | tr -d '\r\n')
            fi
            JAVA_HOME=$(wslpath "$WIN_JAVA" 2>/dev/null || true)
            [[ -n "$JAVA_HOME" ]] && export JAVA_HOME && ok "Auto-detected JAVA_HOME: $JAVA_HOME"
          fi
          ;;

        gitbash)
          # Ask Windows via cmd.exe, then normalise the path for Git Bash.
          WIN_JAVA=$(cmd.exe /c "echo %JAVA_HOME%" 2>/dev/null | tr -d '\r\n')
          if [[ -n "$WIN_JAVA" && "$WIN_JAVA" != "%JAVA_HOME%" ]]; then
            # Convert  C:\Program Files\Java\jdk-17  →  /c/Program Files/Java/jdk-17
            JAVA_HOME=$(echo "$WIN_JAVA" | sed 's|\\|/|g; s|^\([A-Za-z]\):|/\L\1|')
            export JAVA_HOME
            ok "Auto-detected JAVA_HOME: $JAVA_HOME"
          fi
          ;;

        macos)
          if [[ -x "/usr/libexec/java_home" ]]; then
            JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
            [[ -n "$JAVA_HOME" ]] && export JAVA_HOME && ok "Auto-detected JAVA_HOME: $JAVA_HOME"
          fi
          ;;

        *)
          # Linux — derive from java on PATH
          JAVA_BIN=$(command -v java 2>/dev/null || true)
          if [[ -n "$JAVA_BIN" ]]; then
            JAVA_BIN_REAL=$(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")
            JAVA_HOME=$(dirname "$(dirname "$JAVA_BIN_REAL")")
            export JAVA_HOME
            ok "Auto-detected JAVA_HOME: $JAVA_HOME"
          fi
          ;;
      esac
    fi

    if [[ -z "${JAVA_HOME-}" ]]; then
      fail "Cannot determine JAVA_HOME — skipping pom.xml."
      warn "Set JAVA_HOME manually and re-run:"
      case "$PLATFORM" in
        wsl)     warn "  export JAVA_HOME=\$(wslpath '\$(powershell.exe -NoProfile -Command "\$env:JAVA_HOME")')" ;;
        gitbash) warn "  export JAVA_HOME='/c/Program Files/Java/jdk-17'" ;;
        macos)   warn "  export JAVA_HOME=\$(/usr/libexec/java_home)" ;;
        *)       warn "  export JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64'" ;;
      esac
      errors=$(( errors + 1 ))
    else
      # ── 2c. Run Maven ────────────────────────────────────────────────────────
      # .cmd/.bat files are Windows batch scripts — bash cannot execute them.
      # cmd.exe /c also fails with space-in-path quoting when combined with
      # dotted version strings (splits "1.3.0" into "1" + ".3.0").
      # Solution: delegate to powershell.exe, which already has mvn on its
      # Windows PATH and handles quoting correctly.

      if [[ "$MVN_CMD" == *.cmd || "$MVN_CMD" == *.bat ]]; then
        mvn_out=$(powershell.exe -NoProfile -Command \
          "mvn versions:set '-DnewVersion=$NEW_VERSION' '-DgenerateBackupPoms=false' -q" \
          2>&1 | tr -d '\r') \
          && mvn_ok=true || mvn_ok=false
      else
        # Native binary (Linux mvn, macOS mvn, or mvn installed inside WSL)
        mvn_out=$("$MVN_CMD" versions:set \
          "-DnewVersion=$NEW_VERSION" \
          "-DgenerateBackupPoms=false" \
          -q 2>&1) \
          && mvn_ok=true || mvn_ok=false
      fi

      if $mvn_ok; then
        ok "pom.xml → $NEW_VERSION"
      else
        fail "Maven exited with an error — pom.xml was NOT updated."
        echo "$mvn_out" | sed 's/^/       /'
        warn "Run manually: mvn versions:set -DnewVersion=$NEW_VERSION -DgenerateBackupPoms=false"
        errors=$(( errors + 1 ))
      fi
    fi
  fi
fi

# =============================================================================
# 3. plugin.yml via sed
# =============================================================================
PLUGIN_YML="src/main/resources/plugin.yml"
if [[ -f "$PLUGIN_YML" ]]; then
  info "Updating plugin.yml…"
  portable_sed_i "s/^version:.*/version: '$NEW_VERSION'/" "$PLUGIN_YML"
  ok "$PLUGIN_YML → $NEW_VERSION"
fi

# =============================================================================
# Summary
# =============================================================================
echo ""
if (( errors > 0 )); then
  warn "$errors file(s) could not be updated automatically — see above."
  echo ""
fi

echo -e "  ${GRN}Next steps:${NC}"
echo "    1. Add a  ## [$NEW_VERSION]  block to CHANGELOG.md"
[[ $errors -gt 0 ]] && echo "    2. Fix the skipped file(s) listed above, then:"
echo "    3. git add VERSION CHANGELOG.md pom.xml $PLUGIN_YML"
echo "    4. git commit -m \"chore(release): bump version to $NEW_VERSION\""
echo ""
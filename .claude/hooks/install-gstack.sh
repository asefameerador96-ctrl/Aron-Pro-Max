#!/bin/bash
# SessionStart hook: make sure gstack (https://github.com/garrytan/gstack) is
# installed. Cloud sessions start from a fresh container, so gstack has to be
# reinstalled each time. Local machines keep their existing install untouched.
set -uo pipefail

GSTACK_DIR="$HOME/.claude/skills/gstack"

# Already installed (local machine, or a resumed container): nothing to do.
# gstack's own team-mode hook handles auto-updates.
if [ -x "$GSTACK_DIR/browse/dist/browse" ]; then
  exit 0
fi

# Only auto-install in Claude Code on the web; locally, follow CLAUDE.md.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  echo "gstack is not installed. See the gstack section in CLAUDE.md to install it." >&2
  exit 0
fi

{
  mkdir -p "$HOME/.claude/skills"
  if [ ! -d "$GSTACK_DIR/.git" ]; then
    rm -rf "$GSTACK_DIR"
    git clone --single-branch --depth 1 https://github.com/garrytan/gstack.git "$GSTACK_DIR"
  fi
  cd "$GSTACK_DIR" && ./setup --team
} >"$HOME/.gstack-install.log" 2>&1 || {
  echo "gstack install failed; see ~/.gstack-install.log" >&2
  exit 0  # never block the session
}

echo "gstack installed ($(cat "$GSTACK_DIR/VERSION"))."

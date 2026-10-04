# Aron-Pro-Max

## gstack (recommended)

This project uses [gstack](https://github.com/garrytan/gstack): Garry Tan's set of
Claude Code skills that act as a virtual engineering team (CEO, eng manager,
designer, reviewer, QA, security officer, release engineer).

Install it once per machine (needs git and [Bun](https://bun.sh)):

```bash
git clone --single-branch --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack
cd ~/.claude/skills/gstack && ./setup --team
```

Then restart Claude Code. `--team` makes gstack auto-update at the start of each session.
In Claude Code on the web, `.claude/hooks/install-gstack.sh` (a SessionStart hook) installs it
automatically at the start of each session; the log is at `~/.gstack-install.log`.

### Rules

- Use the `/browse` skill from gstack for all web browsing. Never use `mcp__claude-in-chrome__*` tools.
- Use `~/.claude/skills/gstack/...` for gstack file paths.

### The sprint: Think → Plan → Build → Review → Test → Ship → Reflect

| Stage   | Skills |
|---------|--------|
| Think   | `/office-hours` (start here), `/spec` |
| Plan    | `/autoplan`, `/plan-ceo-review`, `/plan-eng-review`, `/plan-design-review`, `/plan-devex-review` |
| Design  | `/design-consultation`, `/design-shotgun`, `/design-html`, `/design-review`, `/diagram` |
| Review  | `/review`, `/cso` (security), `/codex` (second opinion), `/investigate` (debugging), `/deslop-shared-libs`, `/test-audit` |
| Test    | `/qa`, `/qa-only`, `/browse`, `/scrape`, `/benchmark`, `/devex-review`, `/setup-browser-cookies`, `/connect-chrome` |
| Ship    | `/ship`, `/land-and-deploy`, `/canary`, `/setup-deploy`, `/document-release`, `/document-generate` |
| Reflect | `/retro`, `/learn` |
| Safety  | `/careful`, `/freeze`, `/guard`, `/unfreeze` |
| Other   | `/make-pdf`, `/setup-gbrain`, `/gstack-upgrade` |

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

## Android and Azure skills

Vendored in `.claude/skills/`, loaded automatically for this repo. Sources and pinned commits:
`.claude/skills/SOURCES.md`. Update with `scripts/update-agent-skills.sh`, then review and commit.

- **Android** (Kotlin, Jetpack Compose): Google's official [android/skills](https://github.com/android/skills)
  (edge-to-edge, Navigation 3, R8, profiler, intent/permission security, testing setup, Play policy and billing,
  AGP 9) plus `claude-android-ninja` (modular architecture, MVVM, Hilt, Room, Gradle conventions).
  Follow these skills for Android work.
- **Azure**: Microsoft's [azure-skills](https://github.com/microsoft/azure-skills) (`azure-enterprise-infra-planner`,
  `azure-prepare`, `azure-validate`, `azure-deploy`, `azure-reliability`, `azure-diagnostics`, `azure-quotas`, ...)
  plus Microsoft Learn docs skills (`azure-well-architected`, `azure-architecture`, `azure-resiliency`, and per-service guides).
  Design every Azure component against the Well-Architected reliability and performance pillars.
- **MCP servers** (`.mcp.json`): `azure` (Azure MCP Server; needs `az login` for live resources) and
  `microsoftdocs` (Microsoft Learn docs, no sign-in).

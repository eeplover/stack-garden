---
name: idealab-cc-switch-usage
description: Configure or refresh cc-switch's usage detection for the Alibaba "Idea LAB" provider (aistudio.alibaba-inc.com). Use this skill whenever the user mentions idealab and any of cookie / 用量 / usage / 过期 / 刷新 / 配置 — including casual phrasings like "idealab cookie 又过期了", "更新一下 idealab", "cc-switch 里 idealab 显示无效", "帮我配 idealab 的用量". Also use when the user pastes a curl command targeting `aistudio.alibaba-inc.com/api/ailab/ak/teamapi/getOrCreate`. Don't ask whether to use this skill — if the user is talking about idealab usage in cc-switch, just use it.
---

# idealab-cc-switch-usage

Manages the `usage_script` field on the cc-switch "Idea LAB" provider so the desktop app can show today's call count and amount cost on the provider card.

## Why a skill exists for this

The `usage_script` lives in cc-switch's SQLite DB (`~/.cc-switch/cc-switch.db`, table `providers`, column `meta`). The "custom" template — which idealab requires (the usage endpoint host differs from the provider base URL, so cc-switch's same-origin check rules out other templates) — has **no UI input field for credentials**: cookies have to be embedded in the JS code itself. That makes routine cookie refresh annoying without a script. This skill bundles `configure.py` to keep the JS template canonical and let cookie rotation happen in one command.

## When to invoke

Any of these from the user is a green light:

- "idealab cookie 过期了 / 又失效了" — refresh
- "更新 idealab 的 cookie" — refresh
- "刷新 idealab 用量" / "cc-switch 里 idealab 显示无效" — refresh
- "帮我配 idealab 用量检测" — setup (or refresh if already configured)
- "把 idealab 的用量脚本删了" — delete
- They paste a curl command whose URL is `aistudio.alibaba-inc.com/api/ailab/ak/teamapi/getOrCreate`

If unsure between setup vs refresh, run `status` first — it tells you whether `usage_script` is already configured.

## How to invoke

Cookie is **always read from stdin**. Two accepted formats:

1. **Full curl command** (most common — copied from browser DevTools): pass it on stdin as-is. The script extracts the value of `-b` / `--cookie`.
2. **Raw cookie header value** (`k1=v1; k2=v2; ...`): also fine.

Either way, pipe via a heredoc to avoid shell-escaping the cookie's `=`, `;`, `+`, `/` characters.

### Locating `configure.py`

The examples below write `$SKILL_DIR/scripts/configure.py`. **`$SKILL_DIR` is a placeholder, not a real env var** — substitute the actual absolute path of the directory containing this `SKILL.md` before running the command.

Where this skill lives depends on how it was installed and which agent loaded it:

- Claude Code, global: `~/.claude/skills/idealab-cc-switch-usage/`
- Claude Code, project: `<project>/.claude/skills/idealab-cc-switch-usage/`
- OpenCode, global: `~/.config/opencode/skills/idealab-cc-switch-usage/` (path varies by OS)
- Other agents: their own conventional location
- `npx skills add --copy` mode: at the agent's path above, but as an independent copy
- `npx skills add` (default symlink mode): at the agent's path above, symlinked to a canonical copy elsewhere

The agent loader normally announces this path as **"Base directory for this skill: ..."** when injecting the skill into context. Use that. If you genuinely cannot determine it, fall back to `$(dirname "$(realpath SKILL.md)")` from the skill's directory, or ask the user to `find ~ -name configure.py -path '*/idealab-cc-switch-usage/scripts/*'`.

### Refresh (high-frequency case)

```bash
python3 "$SKILL_DIR/scripts/configure.py" refresh <<'COOKIE_EOF'
<paste the curl OR the raw cookie string here>
COOKIE_EOF
```

The script does a pre-flight POST to the usage endpoint with the new cookie before touching the DB. If the endpoint rejects the cookie (`success: false`), nothing is written — you tell the user the cookie didn't work and ask for a fresh one.

On success, print the team name + today's calls/amount from the verification response so the user sees their quota immediately, and remind them to restart cc-switch (or click the bar-chart icon on the provider card to refresh).

### First-time setup

Same as refresh, but the subcommand is `setup`:

```bash
python3 "$SKILL_DIR/scripts/configure.py" setup <<'COOKIE_EOF'
<curl or cookie>
COOKIE_EOF
```

If `usage_script` already exists, `setup` refuses unless you pass `--force`. In practice, prefer `refresh` over `setup --force`.

### Delete

```bash
python3 "$SKILL_DIR/scripts/configure.py" delete
```

No stdin needed.

### Status / verify / regenerate

- `status` — read-only inspection of the current `usage_script` (templateType, code length, whether a cookie is inlined, whether the legacy `apiKey` field is set).
- `verify` — pipe a cookie in; the script POSTs to the endpoint and prints the team's quota, but writes nothing.
- `regenerate` — re-apply the current `SCRIPT_TEMPLATE` (in `configure.py`) using the cookie already inlined in the DB. Use after editing the template in the script (e.g. tweaking the extractor's display format) so existing installs pick up the new JS without asking the user for the cookie again.

Use `status` to decide between setup vs refresh when the user's intent is ambiguous. Use `verify` when the user just wants to test a cookie without committing. Use `regenerate` when you've changed `SCRIPT_TEMPLATE` and need to push it to a live install.

## Where to get the cookie (instruct the user)

Tell the user to open **https://aistudio.alibaba-inc.com/#/aistudio/manage/personalResource** (this is the page that fires the team-info API call), then grab the `getOrCreate` request from DevTools' Network tab and paste it as curl. If they don't know how to copy a request as cURL, walk them through it on the fly — it's standard browser DevTools usage.

If the `getOrCreate` request doesn't appear, the user likely doesn't have a team with code `API_TEAM_CODE_99`; ask which team they actually use.

## Extracting the cookie from user input

If the user pastes the curl in the chat (not into a file), read the message, then write the curl verbatim to a temp file or use a heredoc. Don't try to manually pull out `-b '...'` and re-quote it — that's exactly what the script's curl parser is for, and the cookie often contains characters that bite shell quoting.

If the user just gives a raw cookie string, same deal — heredoc it in.

## Customization flags worth knowing

- `--provider` — provider id (UUID) or name substring. Defaults to `Idea LAB`. Use this if the user has renamed the provider or has multiple matches.
- `--db` — alternate DB path (rarely needed).
- `--no-verify` — skip the pre-flight POST. Only use if the user explicitly says "don't verify" or the network can't reach `aistudio.alibaba-inc.com`.
- `--force` — `setup` only; overwrite existing `usage_script`.

## After writing

cc-switch caches `meta` in memory at startup. Always tell the user one of:
- "重启 cc-switch 就生效"
- "或者在 provider 卡片上点柱状图 / 用量刷新按钮"

Otherwise they'll think nothing happened.

## Failure modes & how to react

- **Exit 2** — bad input (empty stdin, missing cookie keys, curl without `-b`, or `setup` on an already-configured provider). Tell the user what was wrong; usually they need to re-copy the curl with cookies included.
- **Exit 3** — provider not found or ambiguous. The error message lists matches; ask the user which one or pass `--provider <uuid>`.
- **Exit 4** — cookie verification failed (network error or `success: false`). The cookie is dead or wrong; ask for a fresh copy from the browser.
- **Exit 5** — DB file missing. cc-switch may not be installed at the standard path; ask for the correct location and pass `--db`.

## Anti-patterns

- Don't write the cookie to `usage_script.apiKey` and reference it as `{{apiKey}}` — that field is invisible in the UI for the "custom" template, so the user can't edit it through the modal. The whole point of inlining the cookie is that the user can see and edit it via the script editor in the usage modal.
- Don't switch the templateType away from `custom` — the General/NewAPI templates enforce same-origin between `baseUrl` and `request.url`, and idealab's usage endpoint host (`aistudio.alibaba-inc.com`) differs from its API base (`idealab.alibaba-inc.com`).
- Don't try to use cc-switch's UI to do any of this — there's no input field for the cookie under the custom template.

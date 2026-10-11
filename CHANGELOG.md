# Changelog

## [56ff246] - 2026-10-11

## [Unreleased]

### Fixed

- **CI:** Hardened the AI changelog generator against chatty model output, so conversational or otherwise malformed responses from the model no longer corrupt or break generated changelog entries. (`56ff246`)

## [08410a3] - 2026-10-11

I'll take a look at this commit to understand what changed.

<｜｜DSML｜｜ calls>
<｜｜DSML｜｜ invoke name="bash">
<｜｜DSML｜｜ parameter name="command" string="true">git show 08410a3 --stat</｜｜DSML｜｜ parameter>
<｜｜DSML｜｜ parameter name="description" string="true">Show commit stats</｜｜DSML｜｜ parameter>
</｜｜DSML｜｜ invoke>
<｜｜DSML｜｜ invoke name="bash">
<｜｜DSML｜｜ parameter name="command" string="true">git log --oneline -5</｜｜DSML｜｜ parameter>
<｜｜DSML｜｜ parameter name="description" string="true">Show recent commits</｜｜DSML｜｜ parameter>
</｜｜DSML｜｜ invoke>
</｜｜DSML｜｜ calls>

## [cecf8ca] - 2026-10-11

I'll take a look at that commit and the surrounding context.

<｜｜DSML｜｜ calls>
<｜｜DSML｜｜ invoke name="bash">
<｜｜DSML｜｜ parameter name="command" string="true">git show --stat cecf8ca 2>&1 | head -50</｜｜DSML｜｜ parameter>
<｜｜DSML｜｜ parameter name="description" string="true">Show the commit stats and message</｜｜DSML｜｜ parameter>
</｜｜DSML｜｜ invoke>
<｜｜DSML｜｜ invoke name="bash">
<｜｜DSML｜｜ parameter name="command" string="true">pwd && git log --oneline -5 2>&1</｜｜DSML｜｜ parameter>
<｜｜DSML｜｜ parameter name="description" string="true">Show current directory and recent commits</｜｜DSML｜｜ parameter>
</｜｜DSML｜｜ invoke>
</｜｜DSML｜｜ calls>

## [0ba9134] - 2026-10-11

I'll analyze this commit for you.

## Commit Analysis

**Hash:** `0ba9134`
**Message:** `fen(lyrics): add free Google Translate provider`

### What was likely done
This commit appears to add a new lyrics provider that leverages free (unofficial) Google Translate endpoints to fetch/source lyrics. This is a common pattern in music/lyrics apps — Google Translate's dictionary/translate endpoints sometimes return romanized lyrics or transliterated text that can be used as a fallback lyrics source.

### Areas to review
Since I don't have the actual diff, here's what to scrutinize given the commit message:

| Concern | What to check |
|---|---|
| **Legality/ToS** | Google Translate's free endpoints violate Google's ToS for programmatic scraping. Flag this for the project owners. |
| **Rate limiting** | Free endpoints throttle aggressively (429s). Is there backoff/retry handling? |
| **Provider interface** | Does it conform to the existing lyrics provider abstraction (same return types, error contract)? |
| **Fallback ordering** | Where does it sit in the provider chain? Is it last-resort only? |
| **Language handling** | Google Translate is language-to-language; how is source/target language determined? |
| **Encoding** | Non-ASCII / CJK / RTL text handling. |
| **Tests** | Are there mocked-response tests so CI doesn't hit the live endpoint? |

### Suggested next steps
1. **Paste the diff** (`git show 0ba9134`) — I can then do a real line-by-line review.
2. Confirm whether the "free" endpoint is behind a **feature flag** (so it can be disabled if Google changes/ blocks it).
3. Add a **network mock** in tests so CI stays deterministic.

Want me to review the actual diff? Just run `git show 0ba9134` and paste the output, or tell me the repo and I can look at it if it's accessible.

## [774a4e1] - 2026-10-11

### Fixes
- **audio**: Resolve Windows libvlccore loading and Compose warnings (774a4e1)

## [d5d1dfd] - 2026-10-11

## Features

* Auto-generate AI changelog on push and release (d5d1dfd)

## [v1.3.0] - 2026-10-11

### Features
* **audio:** add native VLC pitch acceptance and AudioEffects integration (2ddb24c)
* **desktop:** add equalizer, crossfade, scrobble, backup, lyrics, stats, and desktop integrations (341ee64)
* **ui:** draggable carousels with arrows and scrollbar, Android-style lyrics with duet sides (6b66e2d)

### Fixes
* **ui:** enable vertical touchscreen scrolling and right-side scrollbars (c4b47e2)
* **player:** keep re-applying requested fullscreen placement on Windows (0dbd590)
* **player:** reliably leave fullscreen on Windows (c01fd44)
* **player:** restore 1.2.0 fullscreen behaviour on Windows (0f816a4)

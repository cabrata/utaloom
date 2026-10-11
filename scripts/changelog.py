#!/usr/bin/env python3
# ponytail: simple stdlib urllib client; upgrade to httpx/sdk if retries or streaming needed.
import argparse
import datetime
import http.client
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "https://9router.caliph.dev/v1"
API_URL = os.getenv("OPENAI_BASE_URL", "").strip().rstrip("/")
if not API_URL:
    API_URL = DEFAULT_BASE_URL
API_URL += "/chat/completions"
API_KEY = os.getenv("OPENAI_API_KEY")
MODEL = os.getenv("AI_MODEL") or "combo"
CHANGELOG_FILE = os.getenv("CHANGELOG_FILE", "CHANGELOG.md")
MAIN_HEADER = "# Changelog\n\n"

# Commits that are changelog noise (produced by this tooling / version bumps).
NOISE_RE = re.compile(r"^(docs\(changelog\)|chore\(release\)):", re.IGNORECASE)

SYSTEM_PROMPT = (
    "You are an automated changelog generator. You convert a raw git commit list for a "
    "single release into a concise changelog section.\n"
    "Rules:\n"
    "- Reply with ONLY the changelog content. Never ask questions, never add a preamble, "
    "never offer options, never mention being an AI.\n"
    "- Use Keep a Changelog headings, only the ones that apply, in this order: "
    "'### Added', '### Changed', '### Fixed', '### Removed'.\n"
    "- Each entry is one bullet line: '- **scope:** summary (`hash`)'.\n"
    "- Drop chore/release/bump/merge noise unless it is notable.\n"
    "- Do not wrap the output in markdown code fences."
)

# Phrases the model uses when it chats back instead of producing a changelog.
CHATTER_RE = re.compile(
    r"(would you like|what would you like|let me know|here'?s a quick draft|"
    r"if you want|do you want|i can |i'?ll take|which direction|which format|"
    r"tell me|feel free|as an ai|sure[,!]|certainly|happy to)",
    re.IGNORECASE,
)


def git_cmd(*args):
    try:
        res = subprocess.check_output(["git", *args], stderr=subprocess.DEVNULL)
        return res.decode("utf-8").strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""


def clean_markdown_fences(text):
    text = text.strip()
    text = re.sub(r"^```[^\n]*\n?", "", text)
    text = re.sub(r"\n?```$", "", text)
    return text.strip()


def filter_noise(commits):
    kept = [line for line in commits.splitlines() if line.strip() and not NOISE_RE.match(line.lstrip("* ").strip())]
    return "\n".join(kept)


def list_release_tags():
    """All SemVer release tags (vX.Y.Z), oldest first."""
    out = git_cmd("tag", "--sort=v:refname", "--list", "v[0-9]*")
    tags = [t for t in out.splitlines() if re.fullmatch(r"v\d+\.\d+\.\d+", t.strip())]
    return tags


def tag_date(tag):
    date = git_cmd("log", "-1", "--format=%cs", tag)
    return date or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")


def get_commits_for_range(rev_range):
    """Commit lines for a git revision range, with noise filtered out."""
    if not rev_range:
        return ""
    return filter_noise(git_cmd("log", rev_range, "--pretty=format:* %s (%h)"))


def get_commits_for_release(tag):
    """Commits introduced by `tag`, i.e. everything since the previous release."""
    tags = list_release_tags()
    if tag not in tags:
        # The tag does not exist yet (release workflow runs before tagging):
        # take everything since the latest existing release.
        return get_commits_since_latest_tag()
    idx = tags.index(tag)
    prev = tags[idx - 1] if idx > 0 else None
    rev_range = f"{prev}..{tag}" if prev else tag
    return get_commits_for_range(rev_range)


# Map whatever section wording the model emits onto Keep a Changelog headings.
HEADING_MAP = {
    "added": "### Added",
    "add": "### Added",
    "feature": "### Added",
    "features": "### Added",
    "new": "### Added",
    "changed": "### Changed",
    "change": "### Changed",
    "changes": "### Changed",
    "improvement": "### Changed",
    "improvements": "### Changed",
    "deprecated": "### Changed",
    "fixed": "### Fixed",
    "fix": "### Fixed",
    "fixes": "### Fixed",
    "bug fix": "### Fixed",
    "bug fixes": "### Fixed",
    "removed": "### Removed",
    "remove": "### Removed",
    "removals": "### Removed",
    "deprecations": "### Removed",
}


def normalize_heading(line):
    """Return a canonical '### X' heading, or None if the line is not a section."""
    m = re.match(r"^#{2,4}\s+(.*?)\s*$", line.strip())
    if not m:
        return None
    title = m.group(1).strip().rstrip(":").strip().lower()
    title = re.sub(r"[^a-z ]", "", title).strip()
    return HEADING_MAP.get(title)


def strip_code_fences(text):
    """Remove fenced code blocks (``` ... ```) entirely."""
    out = []
    in_fence = False
    for line in text.splitlines():
        if line.strip().startswith("```"):
            in_fence = not in_fence
            continue
        if not in_fence:
            out.append(line)
    return "\n".join(out)


# Bullets that are really notes from the model to the user, not changelog items.
META_RE = re.compile(
    r"("
    r"^\s*[*-]\s*\*{0,2}(format|version|date|scope label|note|commit|order)\*{0,2}:|"
    r"\bkeep a changelog\b|\bunreleased\b|\bconvention\b|\bswap the heading\b|"
    r"\bdrop-in\b|\bno version/date header\b|\bbreaking/api surface\b|"
    r"\bcommit:\s*`|\bthe version/date\b|\bassume[sd]?\b|\badjust if\b|"
    r"\bgrouped under\b|\bdon'?t affect runtime\b|\bsuperseded later\b|\bno code changes\b|"
    r"\bI (used|kept|left|dropped|treated|put|described|can|could|would|omitted|assumed|merged)\b|"
    r"\bI'll\b|\bI've\b|\bI will\b|\btell me\b|\blet me know\b|\byou'?d prefer\b|"
    r"\bif you (use|want|prefer|need|'?d)\b|\bif the CI\b|\bif your changelog\b|"
    r"\bif any of these\b|\brather than the implementation\b|\bso I \b|\bthe UI work is\b|"
    r"\bthese (two|three|five|four|six) commits\b|\bship together\b|\bnet effect is zero\b"
    r")",
    re.IGNORECASE,
)


def fix_links(text):
    """Replace markdown links with their text when the target is not a real URL."""
    def repl(m):
        label, target = m.group(1), m.group(2)
        if re.match(r"^https?://", target) or target.startswith("#"):
            return m.group(0)
        return label
    return re.sub(r"\[([^\]]+)\]\(([^)]*)\)", repl, text)


def dedupe_bullets(lines):
    """Drop bullets that substantially restate a longer bullet (token overlap)."""
    def tokens(s):
        return {w for w in re.findall(r"[a-z0-9]+", s.lower()) if len(w) > 2}

    bullets = [i for i, l in enumerate(lines) if l.startswith("- ")]
    drop = set()
    for i in range(len(bullets)):
        for j in range(i + 1, len(bullets)):
            a, b = bullets[i], bullets[j]
            if a in drop or b in drop:
                continue
            ta, tb = tokens(lines[a]), tokens(lines[b])
            if not ta or not tb:
                continue
            overlap = len(ta & tb) / min(len(ta), len(tb))
            if overlap >= 0.7:
                shorter = a if len(lines[a]) <= len(lines[b]) else b
                drop.add(shorter)
    return [l for i, l in enumerate(lines) if i not in drop]


def sanitize_section(text):
    """Keep only canonical '### Section' headings and '- ' bullets.

    Drops model chatter, stray '## [Unreleased]' headings, horizontal rules,
    prose paragraphs, code fences, and bullets that are really meta-notes to the
    user. Bullets that appear before any heading are collected under
    '### Changed'.
    """
    text = fix_links(strip_code_fences(text))
    lines = []
    current = None
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        heading = normalize_heading(stripped)
        if heading:
            if heading == current:
                continue  # merge duplicate consecutive sections
            if lines and lines[-1] != "":
                lines.append("")
            lines.append(heading)
            current = heading
            continue
        if stripped.startswith(("- ", "* ")):
            item = stripped[2:].strip()
            if META_RE.search(item):
                continue  # model commentary, not a changelog entry
            if current is None:
                lines.append("")
                lines.append("### Changed")
                current = "### Changed"
            lines.append("- " + item)

    lines = dedupe_bullets(lines)

    # Drop section headings that ended up with no bullets under them.
    pruned = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if re.match(r"^###\s", line):
            has_bullet = i + 1 < len(lines) and lines[i + 1].startswith("- ")
            if not has_bullet:
                i += 1
                continue
        pruned.append(line)
        i += 1

    # Collapse any leftover blank-line runs.
    compact = []
    for line in pruned:
        if line == "" and (not compact or compact[-1] == ""):
            continue
        compact.append(line)

    body = "\n".join(compact).strip()
    return body


def extract_changelog_section(text):
    """Return a clean changelog body, or "" when nothing usable was produced."""
    body = sanitize_section(text)
    if not any(l.strip().startswith(("- ", "* ")) for l in body.splitlines()):
        return ""
    return body


def has_chatter(section):
    """True when a non-bullet, non-heading line reads like model chatter."""
    for line in section.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("- ", "* ")) or stripped.startswith("#"):
            continue
        if CHATTER_RE.search(stripped):
            return True
    return False


def call_api(messages, temperature=0.2, attempts=4):
    payload = {
        "model": MODEL,
        "stream": False,
        "temperature": temperature,
        "messages": messages,
    }
    req = urllib.request.Request(
        API_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {API_KEY}",
        },
    )
    last_err = None
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                res = json.loads(resp.read().decode("utf-8"))
            return clean_markdown_fences(res["choices"][0]["message"]["content"])
        except (urllib.error.URLError, http.client.HTTPException, OSError, KeyError, ValueError) as err:
            last_err = err
            if attempt < attempts:
                time.sleep(2 * attempt)
    raise RuntimeError(f"AI request failed after {attempts} attempts: {last_err}")


def generate_changelog_raw(commits):
    """Ask the model for a changelog section and return its raw text."""
    if not commits or not API_KEY:
        return commits

    user_msg = f"Commits:\n{commits}\n\nWrite the changelog section now."
    raw = call_api([
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_msg},
    ])
    # The model sometimes chats back instead of writing the changelog; retry once.
    if not extract_changelog_section(raw) or has_chatter(extract_changelog_section(raw)):
        raw = call_api([
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_msg},
            {"role": "assistant", "content": "Understood."},
            {"role": "user", "content": "Output only the changelog section. No preamble, no questions."},
        ], temperature=0.0)
    return raw


def generate_changelog_ai(commits):
    if not commits:
        return "* No changes recorded."
    if not API_KEY:
        # No API key: emit the raw commit list instead of failing the workflow.
        return commits

    section = extract_changelog_section(generate_changelog_raw(commits))
    if not section:
        return commits
    return section


def get_commits_since_latest_tag():
    """Commits since the most recent existing release tag (for a brand-new release)."""
    tags = list_release_tags()
    if tags:
        return get_commits_for_range(f"{tags[-1]}..HEAD")
    return filter_noise(git_cmd("log", "-n", "50", "--pretty=format:* %s (%h)"))


def format_release_entry(tag, content, date_str=None):
    content = (content or "").strip()
    date_str = date_str or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    return f"## [{tag}] - {date_str}\n\n{content}\n"


def write_changelog(entries, file_path=CHANGELOG_FILE):
    """Write CHANGELOG.md from a list of release entries (newest first)."""
    body = "\n\n".join(e.rstrip() for e in entries if e and e.strip())
    content = MAIN_HEADER + (body + "\n" if body else "")
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)
    return content


def prepend_changelog_entry(title, content, file_path=CHANGELOG_FILE):
    date_str = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    content = (content or "").strip()
    header = f"## [{title}] - {date_str}\n\n{content}\n\n"

    existing = ""
    if os.path.exists(file_path):
        with open(file_path, "r", encoding="utf-8") as f:
            existing = f.read()

    # Avoid duplicate headers (match the whole heading, not a prefix of another title).
    if re.search(rf"^##\s*\[{re.escape(title)}\]", existing, re.MULTILINE):
        return False

    if existing.startswith("# Changelog"):
        body = existing[len("# Changelog"):].lstrip("\n")
        new_content = f"{MAIN_HEADER}{header}{body}"
    else:
        new_content = f"{MAIN_HEADER}{header}{existing}".strip() + "\n"

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(new_content)
    return True


def rebuild_changelog(file_path=CHANGELOG_FILE, dry_run=False, cache_dir=None):
    """Regenerate CHANGELOG.md from scratch, one entry per release tag (newest first).

    When `cache_dir` is set, each release's raw model output is cached there and
    reused on later runs (handy for iterating on the sanitizer without re-calling
    the API).
    """
    tags = list_release_tags()
    if not tags:
        print("No release tags found; nothing to rebuild.")
        return []

    if cache_dir:
        os.makedirs(cache_dir, exist_ok=True)

    entries = []
    for tag in reversed(tags):
        commits = get_commits_for_release(tag)
        cache_file = os.path.join(cache_dir, f"{tag}.txt") if cache_dir else None
        try:
            if cache_file and os.path.exists(cache_file):
                with open(cache_file, "r", encoding="utf-8") as f:
                    raw = f.read()
            else:
                raw = generate_changelog_raw(commits)
                if cache_file:
                    with open(cache_file, "w", encoding="utf-8") as f:
                        f.write(raw)
            notes = extract_changelog_section(raw) or (commits or "* No changes recorded.")
        except Exception as err:  # never abort the whole rebuild on one bad release
            print(f"  {tag}: AI failed ({err}); using raw commit list")
            notes = commits or "* No changes recorded."
        entries.append(format_release_entry(tag, notes, tag_date(tag)))
        print(f"  {tag}: {len(commits.splitlines())} commits -> {len(notes.splitlines())} lines")

    if not dry_run:
        write_changelog(entries, file_path)
    return entries


def run_self_test():
    # 1. Test fence cleaner
    raw_md = "```markdown\n### Features\n* Test (abc1234)\n```"
    assert clean_markdown_fences(raw_md) == "### Features\n* Test (abc1234)", "Failed fence stripping"
    assert clean_markdown_fences("```\n### Fixes\n* x\n```") == "### Fixes\n* x", "Failed bare fence stripping"

    # 2. Test noise filter (changelog commits and version bumps are noise)
    noisy = (
        "* feat: a (aaa1111)\n"
        "* docs(changelog): update CHANGELOG.md [skip ci] (bbb2222)\n"
        "* chore(release): bump version to 1.3.0 (ddd4444)\n"
        "* fix: b (ccc3333)"
    )
    assert filter_noise(noisy) == "* feat: a (aaa1111)\n* fix: b (ccc3333)", "Failed noise filter"

    # 3. Test sanitizer drops model preamble, chatter, fences, and stray headings
    chatty = (
        "What would you like me to do?\n\n## Changelog\n\n## [Unreleased]\n\n"
        "### Added\n- **a:** b (abc1234)\n\n---\n\n"
        "If your changelog uses another convention:\n\n"
        "```markdown\n## [1.2.1]\n- x\n```\n\nLet me know which format you prefer.\n"
    )
    assert extract_changelog_section(chatty) == "### Added\n- **a:** b (abc1234)", "Failed sanitize"
    # Non-canonical headings are normalized; bullets before a heading get a section.
    mixed = "## Features\n- f (1)\n## Fixes\n- x (2)\n## Fixes\n- y (3)\n- orphan (4)"
    assert extract_changelog_section(mixed) == (
        "### Added\n- f (1)\n\n### Fixed\n- x (2)\n- y (3)\n- orphan (4)"
    ), "Failed heading normalization"
    # Meta-notes to the user are dropped, and empty sections are pruned.
    meta = (
        "### Fixed\n- **player:** real fix ([0f816a4])\n"
        "- **Format:** tell me and I'll reformat.\n"
        "- **Version:** I used `[Unreleased]`; swap the heading if needed.\n"
    )
    assert extract_changelog_section(meta) == "### Fixed\n- **player:** real fix ([0f816a4])", "Failed meta drop"
    # Placeholder links are unwrapped; real URLs are kept.
    assert fix_links("- x ([c0a42f5](link-to-commit))") == "- x (c0a42f5)", "Failed placeholder link fix"
    assert fix_links("[a](https://x/y)") == "[a](https://x/y)", "Real link should be kept"
    # Near-duplicate bullets are collapsed to the longer one.
    dup = "### Added\n- **listentogether:** default server and v1+v2 signing (a810c37)\n- **listentogether:** default the server (a810c37)"
    assert extract_changelog_section(dup) == (
        "### Added\n- **listentogether:** default server and v1+v2 signing (a810c37)"
    ), "Failed bullet dedupe"
    assert extract_changelog_section("Just prose, no bullets.") == "", "Should reject bullet-less output"
    assert strip_code_fences("a\n```\ncode\n```\nb") == "a\nb", "Failed fence removal"
    assert has_chatter("### Added\n- a (1)\nTell me which format you prefer."), "has_chatter missed prose"
    assert not has_chatter("### Added\n- **a:** b (abc1234)"), "has_chatter false positive"

    # 4. Test changelog prepend
    tmp_path = ".tmp_changelog_test.md"
    try:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        appended = prepend_changelog_entry("v0.0.1", "### Features\n* First", tmp_path)
        assert appended is True, "First append failed"
        with open(tmp_path, "r", encoding="utf-8") as f:
            text = f.read()
        assert "# Changelog" in text and "## [v0.0.1]" in text and "* First" in text
        # Deduplication check
        appended2 = prepend_changelog_entry("v0.0.1", "### Features\n* First", tmp_path)
        assert appended2 is False, "Duplicate should not be re-added"
        # Similar-but-different title must still be added.
        appended3 = prepend_changelog_entry("v0.0.10", "### Fixes\n* Later", tmp_path)
        assert appended3 is True, "Distinct title should be added"
        # Header must remain the very first line after repeated prepends.
        with open(tmp_path, "r", encoding="utf-8") as f:
            assert f.read().startswith("# Changelog\n\n## [v0.0.10]"), "Header order broken"
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)

    # 5. Test changelog rebuild writes releases newest-first
    tmp_path = ".tmp_changelog_rebuild.md"
    try:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        entries = [
            format_release_entry("v1.1.0", "### Added\n- new", "2026-01-02"),
            format_release_entry("v1.0.0", "### Added\n- first", "2026-01-01"),
        ]
        write_changelog(entries, tmp_path)
        with open(tmp_path, "r", encoding="utf-8") as f:
            text = f.read()
        assert text.startswith("# Changelog\n\n## [v1.1.0] - 2026-01-02"), "Rebuild header/order broken"
        assert text.index("## [v1.1.0]") < text.index("## [v1.0.0]"), "Releases not newest-first"
        assert text.rstrip().endswith("- first"), "Oldest release should be last"
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)

    print("self-test: OK")


def main():
    parser = argparse.ArgumentParser(description="Generate release-based changelog using AI")
    parser.add_argument(
        "--release",
        nargs="?",
        const="",
        help="Release mode: regenerate the entry for a tag (e.g. v1.3.0). "
        "Use without a value to pick up the latest tag.",
    )
    parser.add_argument("--output", help="Save generated notes to output file (e.g. release-notes.md)")
    parser.add_argument(
        "--rebuild",
        action="store_true",
        help="Rebuild CHANGELOG.md from scratch, one entry per release tag (newest first).",
    )
    parser.add_argument(
        "--cache-dir",
        help="With --rebuild: cache each release's raw model output here and reuse it "
        "(speeds up iterating on the sanitizer).",
    )
    parser.add_argument("--test", action="store_true", help="Run self test")
    args = parser.parse_args()

    if args.test:
        run_self_test()
        sys.exit(0)

    if args.rebuild:
        print(f"Rebuilding {CHANGELOG_FILE} from {len(list_release_tags())} releases...")
        entries = rebuild_changelog(cache_dir=args.cache_dir)
        print(f"Rebuilt {CHANGELOG_FILE} with {len(entries)} release entries.")
        sys.exit(0)

    if args.release is not None:
        tag = args.release.strip()
        if not tag:
            tags = list_release_tags()
            if not tags:
                print("No release tags found; nothing to do.")
                sys.exit(0)
            tag = tags[-1]
        commits = get_commits_for_release(tag)
        notes = generate_changelog_ai(commits)
        if args.output:
            with open(args.output, "w", encoding="utf-8") as f:
                f.write(notes + "\n")
        entry = format_release_entry(tag, notes, tag_date(tag))
        # Rebuild from tags so the file always reflects the full release history.
        existing = ""
        if os.path.exists(CHANGELOG_FILE):
            with open(CHANGELOG_FILE, "r", encoding="utf-8") as f:
                existing = f.read()
        if re.search(rf"^##\s*\[{re.escape(tag)}\]", existing, re.MULTILINE):
            print(f"Changelog already contains {tag}; leaving it unchanged.")
            sys.exit(0)
        body = existing[len("# Changelog"):].lstrip("\n") if existing.startswith("# Changelog") else existing
        write_changelog([entry, body.strip()] if body.strip() else [entry])
        print(f"Changelog updated for release {tag}")
        sys.exit(0)

    parser.print_help()


if __name__ == "__main__":
    main()

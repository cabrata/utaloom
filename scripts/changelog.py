#!/usr/bin/env python3
# ponytail: simple stdlib urllib client; upgrade to httpx/sdk if retries or streaming needed.
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import urllib.request

DEFAULT_BASE_URL = "https://9router.caliph.dev/v1"
API_URL = os.getenv("OPENAI_BASE_URL", "").strip().rstrip("/")
if not API_URL:
    API_URL = DEFAULT_BASE_URL
API_URL += "/chat/completions"
API_KEY = os.getenv("OPENAI_API_KEY")
MODEL = os.getenv("AI_MODEL") or "combo"
CHANGELOG_FILE = "CHANGELOG.md"
MAIN_HEADER = "# Changelog\n\n"

# Commits that are changelog noise (they are produced by this tooling itself).
NOISE_RE = re.compile(r"^docs\(changelog\):", re.IGNORECASE)

SYSTEM_PROMPT = (
    "You are an automated changelog generator. You convert a raw git commit list into a "
    "concise CHANGELOG section.\n"
    "Rules:\n"
    "- Reply with ONLY the changelog content. Never ask questions, never add a preamble, "
    "never offer options, never mention being an AI.\n"
    "- Group entries under the headings '### Features', '### Fixes', '### Improvements'; "
    "omit a heading when it has no entries.\n"
    "- Each entry is one bullet line: '- **scope:** summary (hash)'.\n"
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


def get_commits_for_range(rev_range=None):
    if rev_range and not set(rev_range) <= {"0"}:
        commits = git_cmd("log", rev_range, "--pretty=format:* %s (%h)")
        if commits:
            return filter_noise(commits)
        # Range resolved but produced nothing (e.g. a branch deletion); fall through.
    # Fallback to recent commits
    return filter_noise(git_cmd("log", "-n", "10", "--pretty=format:* %s (%h)"))


def get_commits_since_last_tag():
    # Prefer the last tag reachable from HEAD^ (the version this release follows).
    last_tag = git_cmd("describe", "--tags", "--abbrev=0", "HEAD^")
    if last_tag:
        commits = git_cmd("log", f"{last_tag}..HEAD", "--pretty=format:* %s (%h)")
        if commits:
            return filter_noise(commits)
    # Otherwise the most recent tag overall.
    last_tag = git_cmd("describe", "--tags", "--abbrev=0")
    if last_tag:
        commits = git_cmd("log", f"{last_tag}..HEAD", "--pretty=format:* %s (%h)")
        if commits:
            return filter_noise(commits)
    return filter_noise(git_cmd("log", "-n", "20", "--pretty=format:* %s (%h)"))


def extract_changelog_section(text):
    """Drop any model preamble/trailing chatter and keep only the changelog body.

    Returns "" when the model produced no bullet points at all.
    """
    lines = text.splitlines()
    start = None
    for i, line in enumerate(lines):
        stripped = line.strip()
        if re.match(r"^#{1,6}\s+\S", stripped) or stripped.startswith(("- ", "* ")):
            start = i
            break
    if start is None:
        return ""

    lines = lines[start:]
    # Drop a redundant leading "## Changelog" heading (we add our own).
    while lines and re.match(r"^#{1,6}\s+changelog\s*$", lines[0].strip(), re.IGNORECASE):
        lines.pop(0)

    # Trim trailing separators/prose the model tacked on after the last bullet.
    end = len(lines)
    while end > 0:
        stripped = lines[end - 1].strip()
        if stripped.startswith(("- ", "* ")) or re.match(r"^#{1,6}\s+\S", stripped):
            break
        end -= 1
    lines = lines[:end]

    body = "\n".join(lines).strip()
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


def call_api(messages, temperature=0.2):
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
    with urllib.request.urlopen(req, timeout=60) as resp:
        res = json.loads(resp.read().decode("utf-8"))
    return clean_markdown_fences(res["choices"][0]["message"]["content"])


def generate_changelog_ai(commits):
    if not commits:
        return "* No changes recorded."
    if not API_KEY:
        # No API key: emit the raw commit list instead of failing the workflow.
        return commits

    user_msg = f"Commits:\n{commits}\n\nWrite the changelog section now."
    raw = call_api([
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_msg},
    ])
    section = extract_changelog_section(raw)

    # The model sometimes chats back instead of writing the changelog; retry once.
    if not section or has_chatter(section):
        raw = call_api([
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_msg},
            {"role": "assistant", "content": "Understood."},
            {"role": "user", "content": "Output only the changelog section. No preamble, no questions."},
        ], temperature=0.0)
        section = extract_changelog_section(raw)

    if not section:
        return commits

    # Last resort: drop any remaining prose lines that read like chatter.
    cleaned = "\n".join(
        l for l in section.splitlines()
        if not l.strip() or l.strip().startswith(("- ", "* ", "#")) or not CHATTER_RE.search(l)
    ).strip()
    if not any(l.strip().startswith(("- ", "* ")) for l in cleaned.splitlines()):
        return commits
    return cleaned


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


def run_self_test():
    # 1. Test fence cleaner
    raw_md = "```markdown\n### Features\n* Test (abc1234)\n```"
    assert clean_markdown_fences(raw_md) == "### Features\n* Test (abc1234)", "Failed fence stripping"
    assert clean_markdown_fences("```\n### Fixes\n* x\n```") == "### Fixes\n* x", "Failed bare fence stripping"

    # 2. Test noise filter
    noisy = "* feat: a (aaa1111)\n* docs(changelog): update CHANGELOG.md [skip ci] (bbb2222)\n* fix: b (ccc3333)"
    assert filter_noise(noisy) == "* feat: a (aaa1111)\n* fix: b (ccc3333)", "Failed noise filter"

    # 3. Test section extraction drops model preamble and trailing chatter
    chatty = (
        "What would you like me to do?\n\n## Changelog\n\n## Features\n"
        "- **a:** b (abc1234)\n\n---\n\nLet me know if you want another format.\n"
    )
    assert extract_changelog_section(chatty) == "## Features\n- **a:** b (abc1234)", "Failed chatter trim"
    assert extract_changelog_section("Just prose, no bullets.") == "", "Should reject bullet-less output"
    assert has_chatter("## Features\n- a (1)\nTell me which format you prefer."), "has_chatter missed prose"
    assert not has_chatter("## Features\n- **a:** b (abc1234)"), "has_chatter false positive"

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

    print("self-test: OK")


def main():
    parser = argparse.ArgumentParser(description="Auto generate changelog using AI")
    parser.add_argument("--push", nargs="?", const="", help="Push mode with optional rev range (e.g. before..sha)")
    parser.add_argument("--release", help="Release mode with tag name (e.g. v1.3.0)")
    parser.add_argument("--output", help="Save generated notes to output file (e.g. release-notes.md)")
    parser.add_argument("--test", action="store_true", help="Run self test")
    args = parser.parse_args()

    if args.test:
        run_self_test()
        sys.exit(0)

    if args.release:
        tag = args.release
        commits = get_commits_since_last_tag()
        notes = generate_changelog_ai(commits)
        if args.output:
            with open(args.output, "w", encoding="utf-8") as f:
                f.write(notes + "\n")
        prepend_changelog_entry(tag, notes)
        print(f"Changelog updated for release {tag}")
        sys.exit(0)

    if args.push is not None:
        rev_range = args.push if args.push else None
        commits = get_commits_for_range(rev_range)
        short_sha = git_cmd("rev-parse", "--short", "HEAD") or "push"
        notes = generate_changelog_ai(commits)
        prepend_changelog_entry(short_sha, notes)
        if args.output:
            with open(args.output, "w", encoding="utf-8") as f:
                f.write(notes + "\n")
        print(f"Changelog updated for commit {short_sha}")
        sys.exit(0)

    parser.print_help()


if __name__ == "__main__":
    main()

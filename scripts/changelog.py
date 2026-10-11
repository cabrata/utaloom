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

API_URL = os.getenv("OPENAI_BASE_URL", "https://9router.caliph.dev/v1").rstrip("/") + "/chat/completions"
API_KEY = os.getenv("OPENAI_API_KEY")
MODEL = os.getenv("AI_MODEL", "combo")
CHANGELOG_FILE = "CHANGELOG.md"


def git_cmd(*args):
    try:
        res = subprocess.check_output(["git", *args], stderr=subprocess.DEVNULL)
        return res.decode("utf-8").strip()
    except subprocess.CalledProcessError:
        return ""


def clean_markdown_fences(text):
    text = re.sub(r"^```(?:markdown)?\s*", "", text.strip())
    text = re.sub(r"\s*```$", "", text.strip())
    return text.strip()


def get_commits_for_range(rev_range=None):
    if rev_range and "0000000000000000000000000000000000000000" not in rev_range:
        commits = git_cmd("log", rev_range, "--pretty=format:* %s (%h)")
        if commits:
            return commits
    # Fallback to recent commits
    return git_cmd("log", "-n", "10", "--pretty=format:* %s (%h)")


def get_commits_since_last_tag():
    last_tag = git_cmd("describe", "--tags", "--abbrev=0", "HEAD^")
    if not last_tag:
        last_tag = git_cmd("describe", "--tags", "--abbrev=0")
    if last_tag:
        commits = git_cmd("log", f"{last_tag}..HEAD", "--pretty=format:* %s (%h)")
        if commits:
            return commits
    return git_cmd("log", "-n", "20", "--pretty=format:* %s (%h)")


def generate_changelog_ai(commits):
    if not commits:
        return "* No changes recorded."
    if not API_KEY:
        return "\n".join(commits.splitlines())

    payload = {
        "model": MODEL,
        "stream": False,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are a changelog generator. Group git commits by Features, Fixes, or Improvements. "
                    "Ignore bump/release/merge noise unless notable. "
                    "Output ONLY markdown bullet points under section headings, without enclosing markdown code fences."
                ),
            },
            {"role": "user", "content": f"Commits:\n{commits}"},
        ],
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
        raw = res["choices"][0]["message"]["content"]
        return clean_markdown_fences(raw)


def prepend_changelog_entry(title, content, file_path=CHANGELOG_FILE):
    date_str = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    header = f"## [{title}] - {date_str}\n\n{content}\n\n"

    existing = ""
    if os.path.exists(file_path):
        with open(file_path, "r", encoding="utf-8") as f:
            existing = f.read()

    # Avoid duplicate headers
    if f"## [{title}]" in existing:
        return False

    main_header = "# Changelog\n\n"
    if existing.startswith("# Changelog"):
        body = existing[len(main_header):].lstrip("\n")
        new_content = f"{main_header}{header}{body}"
    else:
        new_content = f"{main_header}{header}{existing}".strip() + "\n"

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(new_content)
    return True


def run_self_test():
    # 1. Test fence cleaner
    raw_md = "```markdown\n### Features\n* Test (abc1234)\n```"
    assert clean_markdown_fences(raw_md) == "### Features\n* Test (abc1234)", "Failed fence stripping"

    # 2. Test changelog prepend
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

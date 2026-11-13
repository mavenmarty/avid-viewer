#!/usr/bin/env python3
"""Add release entries for new published versions to docs/USER_GUIDE.md.

For every release tag (v1.0.N, or v<major>.<minor>.<patch>.<build> from 1.1.0 on) newer than the newest version already in the guide, this adds a
"### 1.0.N — <date>" entry with the change log (the commit subjects since the previous release),
marks it "(current)", and updates the version named at the top of the guide.

Run from the repository root with full history and tags (actions/checkout fetch-depth: 0).
The release date comes from the GitHub release (via `gh`, when GH_TOKEN is set), else the tag's commit.
"""
import json
import os
import re
import subprocess
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

GUIDE = "docs/USER_GUIDE.md"
MARKER = "<!-- new-releases: entries for new builds are added automatically below this line -->"
ZONE = ZoneInfo("America/Chicago")
DOC_ONLY = ("docs/", ".github/scripts/update_user_guide.py", ".github/workflows/user-guide.yml")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def vkey(v: str) -> tuple:
    return tuple(int(x) for x in v.split("."))


def release_date(tag: str) -> datetime:
    if os.environ.get("GH_TOKEN"):
        try:
            out = subprocess.run(["gh", "release", "view", tag, "--json", "publishedAt"],
                                 check=True, capture_output=True, text=True).stdout
            return datetime.fromisoformat(json.loads(out)["publishedAt"].replace("Z", "+00:00"))
        except Exception:  # fall back to the commit date
            pass
    return datetime.fromisoformat(git("log", "-1", "--format=%cI", tag).strip())


def fmt_date(d: datetime) -> str:
    d = d.astimezone(ZONE)
    hour = d.strftime("%I").lstrip("0")
    return f"{d.strftime('%B')} {d.day}, {d.year}, {hour}:{d.strftime('%M %p')} CT"


def changes(prev_tag: str, tag: str) -> list[str]:
    out = git("log", "--no-merges", "--reverse", "--format=%H%x09%s", f"{prev_tag}..{tag}")
    items = []
    for line in out.splitlines():
        sha, subject = line.split("\t", 1)
        if "[skip ci]" in subject or "[ci skip]" in subject:
            continue
        files = git("show", "--name-only", "--format=", sha).split()
        if files and all(f.startswith(DOC_ONLY) for f in files):
            continue  # documentation-only commit
        # "feat: Profiles" → "Profiles" (the type only decides the version number)
        subject = re.sub(r"^[a-z]+(\([^)]*\))?!?:\s*", "", subject.strip())
        items.append(subject[:1].upper() + subject[1:])
    return items


def main() -> int:
    text = open(GUIDE, encoding="utf-8").read()
    if MARKER not in text:
        print(f"Marker missing from {GUIDE}; nothing done.", file=sys.stderr)
        return 1

    documented = re.findall(r"^### (\d+\.\d+\.\d+) — ", text, flags=re.M)
    # Version shown → its tag. From 1.1.0 tags carry the build number as a 4th part (v1.1.0.190).
    tag_of = {}
    for t in git("tag", "--list", "v*").split():
        m = re.fullmatch(r"v(\d+\.\d+\.\d+)(\.\d+)?", t)
        if m:
            tag_of[m.group(1)] = t
    tags = sorted(tag_of, key=vkey)
    if not documented or not tags:
        print("No documented versions or no tags; nothing done.")
        return 0
    newest = max(documented, key=vkey)
    new = [t for t in tags if vkey(t) > vkey(newest)]
    if not new:
        print(f"Guide is up to date ({newest}).")
        return 0

    prev = newest
    entries = []
    for v in new:
        items = changes(tag_of.get(prev, f"v{prev}"), tag_of[v]) or ["Maintenance build with no app changes"]
        body = "\n".join(f"- {s}" for s in items)
        entries.append(f"### {v} — {fmt_date(release_date(tag_of[v]))}\n\n**Change log**\n\n{body}\n")
        prev = v
    latest = new[-1]

    text = re.sub(r"^(### \d+\.\d+\.\d+ — .*?) \(current\)$", r"\1", text, flags=re.M)
    entries[-1] = entries[-1].replace("\n", " (current)\n", 1)
    block = "\n".join(reversed(entries))  # newest first
    text = text.replace(MARKER, f"{MARKER}\n\n{block.rstrip()}", 1)
    text = re.sub(r"(This guide covers \*\*Avid Viewer )\d+\.\d+\.\d+(\*\*)", rf"\g<1>{latest}\g<2>", text, count=1)

    open(GUIDE, "w", encoding="utf-8").write(text)
    print(f"Added {', '.join(new)} to {GUIDE}.")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(f"version={latest}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

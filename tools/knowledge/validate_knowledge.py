#!/usr/bin/env python3
"""Read-only validator for docs/knowledge/*.md knowledge records.

Checks, against the records defined in docs/knowledge/README.md's "Record format":
  - malformed/duplicate record IDs
  - missing required metadata per record type
  - broken local links (evidence_doc/source/backlog_link/related)
  - missing referenced source files (source_paths)
  - local-fork drift: has a referenced source file changed since the record's
    verified_commit, or does it have uncommitted working-tree changes (also flags when
    verified_commit is not an ancestor of HEAD, since the drift range is misleading then)
  - upstream drift: if upstream_sha is recorded (not "unknown"), has the upstream
    remote moved past it for that path (reported separately from local drift --
    see docs/knowledge/README.md's provenance section for why these must not be
    conflated)

This does not parse prose, rewrite any file, or call the network. It shells out to
`git` (read-only commands only) and the filesystem. No third-party dependencies.

Usage:
    python tools/knowledge/validate_knowledge.py
    python tools/knowledge/validate_knowledge.py --paths docs/knowledge/capability-index.md
    python tools/knowledge/validate_knowledge.py --no-git
    python tools/knowledge/validate_knowledge.py --strict
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

RECORD_RE = re.compile(r"<!--\s*knowledge-record\s*\n(.*?)-->\s*\n(.*?)(?=\n<!--\s*knowledge-record|\Z)", re.DOTALL)
ID_RE = re.compile(r"^(CAP-[A-Z]+-\d{3}|GAME-\d{3}|REUSE-\d{3}|OBS-\d{3})$")

REQUIRED_FIELDS = {
    "capability": ["id", "type", "area", "status", "reachability", "evidence_doc", "verified_commit"],
    "gameplay": ["id", "type", "topic", "source", "verification", "last_checked"],
    "reuse": ["id", "type", "state", "capability_source", "candidate_consumers"],
    "observation": ["id", "type", "topic", "status", "date", "evidence"],
}

# Fields whose value is a repo-relative path (or comma-separated list of paths)
# that must exist on disk. Free-text fields (capability_source, candidate_consumers,
# topic, observation's "evidence" description, etc.) are intentionally not checked.
SINGLE_PATH_FIELDS = ["evidence_doc", "source", "backlog_link"]
LIST_PATH_FIELDS = ["source_paths", "related"]


class Record:
    def __init__(self, fields, body, file, line_no):
        self.fields = fields
        self.body = body.strip()
        self.file = file
        self.line_no = line_no

    def get(self, key, default=""):
        return self.fields.get(key, default).strip()

    def list_field(self, key):
        raw = self.get(key)
        if not raw:
            return []
        return [p.strip() for p in raw.split(",") if p.strip()]


def find_repo_root():
    here = Path(__file__).resolve()
    # tools/knowledge/validate_knowledge.py -> repo root is two parents up
    return here.parents[2]


def run_git(repo_root, args):
    try:
        result = subprocess.run(
            ["git", *args], cwd=repo_root, capture_output=True, text=True, timeout=30
        )
        return result.returncode, result.stdout.strip(), result.stderr.strip()
    except (OSError, subprocess.SubprocessError) as exc:
        return 1, "", str(exc)


def parse_record_block(raw_fields):
    fields = {}
    for line in raw_fields.splitlines():
        line = line.strip()
        if not line or ":" not in line:
            continue
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip()
    return fields


def collect_records(files):
    records = []
    for f in files:
        text = f.read_text(encoding="utf-8")
        line_offsets = [0]
        for m in re.finditer(r"\n", text):
            line_offsets.append(m.end())
        for match in RECORD_RE.finditer(text):
            raw_fields, body = match.group(1), match.group(2)
            fields = parse_record_block(raw_fields)
            line_no = text.count("\n", 0, match.start()) + 1
            records.append(Record(fields, body, f, line_no))
    return records


def check_ids(records, errors):
    seen = {}
    for r in records:
        rid = r.get("id")
        loc = f"{r.file.name}:{r.line_no}"
        if not rid:
            errors.append((loc, "missing id field"))
            continue
        if not ID_RE.match(rid):
            errors.append((loc, f"id '{rid}' does not match an expected prefix (CAP-<AREA>-NNN / GAME-NNN / REUSE-NNN / OBS-NNN)"))
        if rid in seen:
            errors.append((loc, f"duplicate id '{rid}' (also at {seen[rid]})"))
        else:
            seen[rid] = loc


def check_required_fields(records, errors):
    for r in records:
        loc = f"{r.file.name}:{r.line_no} ({r.get('id', '?')})"
        rtype = r.get("type")
        if rtype not in REQUIRED_FIELDS:
            errors.append((loc, f"unknown or missing type '{rtype}'"))
            continue
        for field in REQUIRED_FIELDS[rtype]:
            if not r.get(field):
                errors.append((loc, f"missing required field '{field}' for type '{rtype}'"))


def check_links(records, repo_root, errors):
    for r in records:
        loc = f"{r.file.name}:{r.line_no} ({r.get('id', '?')})"
        for field in SINGLE_PATH_FIELDS:
            value = r.get(field)
            if not value or value.lower() in ("unknown", "n/a", "none"):
                continue
            if value.startswith("http://") or value.startswith("https://"):
                continue
            path = (repo_root / value).resolve()
            if not path.exists():
                errors.append((loc, f"{field} '{value}' does not exist"))
        for field in LIST_PATH_FIELDS:
            for value in r.list_field(field):
                if value.lower() in ("unknown", "n/a", "none"):
                    continue
                if value.startswith("http://") or value.startswith("https://"):
                    continue
                path = (repo_root / value).resolve()
                if not path.exists():
                    errors.append((loc, f"{field} entry '{value}' does not exist"))


def check_local_drift(records, repo_root, warnings, errors):
    for r in records:
        if r.get("type") != "capability":
            continue
        loc = f"{r.file.name}:{r.line_no} ({r.get('id', '?')})"
        sha = r.get("verified_commit")
        if not sha:
            continue
        rc, _, err = run_git(repo_root, ["cat-file", "-e", f"{sha}^{{commit}}"])
        if rc != 0:
            errors.append((loc, f"verified_commit '{sha}' is not a known commit in this repo ({err})"))
            continue
        rc, _, _ = run_git(repo_root, ["merge-base", "--is-ancestor", sha, "HEAD"])
        if rc != 0:
            warnings.append((loc, f"LOCAL FORK DRIFT: verified_commit '{sha[:12]}' is not an ancestor of HEAD — the commit-count/uncommitted checks below still ran, but '..HEAD' does not mean what it normally means here (the commit may be on a different line of history); re-confirm this record was verified against a real ancestor of current HEAD"))
        for path in r.list_field("source_paths"):
            rc, out, _ = run_git(repo_root, ["log", "--oneline", f"{sha}..HEAD", "--", path])
            if rc == 0 and out:
                count = len(out.splitlines())
                warnings.append((loc, f"LOCAL FORK DRIFT: '{path}' has {count} commit(s) since verified_commit '{sha[:12]}' — re-verify this record's claim against current source"))
            rc, out, _ = run_git(repo_root, ["status", "--porcelain", "--", path])
            if rc == 0 and out:
                warnings.append((loc, f"LOCAL FORK DRIFT: '{path}' has uncommitted working-tree changes — re-verify before trusting this record right now"))


def check_upstream_drift(records, repo_root, upstream_ref, warnings, limitations):
    rc, _, _ = run_git(repo_root, ["rev-parse", "--verify", upstream_ref])
    upstream_available = rc == 0
    if not upstream_available:
        limitations.append(f"upstream ref '{upstream_ref}' not resolvable locally — upstream-drift checks skipped for this run (fetch the upstream remote to enable them)")
    for r in records:
        if r.get("type") != "capability":
            continue
        loc = f"{r.file.name}:{r.line_no} ({r.get('id', '?')})"
        upstream_sha = r.get("upstream_sha")
        if not upstream_sha or upstream_sha.lower() in ("unknown", "n/a", "none"):
            continue
        rc, _, err = run_git(repo_root, ["cat-file", "-e", f"{upstream_sha}^{{commit}}"])
        if rc != 0:
            warnings.append((loc, f"UPSTREAM: upstream_sha '{upstream_sha}' is not resolvable locally — cannot check upstream drift for this record ({err})"))
            continue
        if not upstream_available:
            continue
        rc, _, _ = run_git(repo_root, ["merge-base", "--is-ancestor", upstream_sha, upstream_ref])
        if rc != 0:
            warnings.append((loc, f"UPSTREAM: upstream_sha '{upstream_sha[:12]}' is not an ancestor of '{upstream_ref}' — the commit-count check below still ran, but '..{upstream_ref}' does not mean what it normally means here; re-confirm this record's upstream comparison point"))
        for path in r.list_field("source_paths"):
            rc, out, _ = run_git(repo_root, ["log", "--oneline", f"{upstream_sha}..{upstream_ref}", "--", path])
            if rc == 0 and out:
                count = len(out.splitlines())
                warnings.append((loc, f"UPSTREAM DRIFT: '{path}' has {count} upstream commit(s) on {upstream_ref} since upstream_sha '{upstream_sha[:12]}' — a prior byte-identical comparison is not proof of continued equivalence, re-diff before relying on it"))


def default_files(repo_root):
    knowledge_dir = repo_root / "docs" / "knowledge"
    return sorted(p for p in knowledge_dir.glob("*.md") if p.name != "README.md")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--paths", nargs="*", default=None, help="specific docs/knowledge/*.md files to check (default: all except README.md)")
    parser.add_argument("--no-git", action="store_true", help="skip all git-dependent checks (link/required-field checks still run)")
    parser.add_argument("--strict", action="store_true", help="treat drift warnings as failures too")
    parser.add_argument("--upstream-ref", default="upstream/master", help="remote-tracking ref to compare upstream_sha against (default: upstream/master)")
    args = parser.parse_args()

    repo_root = find_repo_root()

    if args.paths:
        files = [Path(p) if Path(p).is_absolute() else repo_root / p for p in args.paths]
        missing = [str(f) for f in files if not f.exists()]
        if missing:
            print(f"ERROR: --paths file(s) not found: {', '.join(missing)}")
            return 2
    else:
        files = default_files(repo_root)

    records = collect_records(files)

    errors = []
    warnings = []
    limitations = [
        "anchor fragments (#section) in links are not verified, only file existence",
        "prose fields (capability_source, candidate_consumers, topic, etc.) are not link-checked, only the designated path fields",
        "a non-ancestor verified_commit/upstream_sha now gets its own warning (see merge-base --is-ancestor checks above), but the subsequent commit-count/uncommitted-change checks still run on the raw '..' range regardless -- treat their output as informational, not authoritative, when that warning is present",
    ]

    check_ids(records, errors)
    check_required_fields(records, errors)
    check_links(records, repo_root, errors)

    if not args.no_git:
        check_local_drift(records, repo_root, warnings, errors)
        check_upstream_drift(records, repo_root, args.upstream_ref, warnings, limitations)
    else:
        limitations.append("--no-git: local-fork and upstream drift checks skipped")

    print(f"docs/knowledge validator — {len(records)} record(s) across {len(files)} file(s)")
    print()

    local_warnings = [w for w in warnings if "LOCAL FORK DRIFT" in w[1]]
    upstream_warnings = [w for w in warnings if "UPSTREAM" in w[1]]
    other_warnings = [w for w in warnings if w not in local_warnings and w not in upstream_warnings]

    def section(title, items):
        if not items:
            return
        print(f"-- {title} ({len(items)}) --")
        for loc, msg in items:
            print(f"  {loc}: {msg}")
        print()

    section("Local fork drift (this fork's own history)", local_warnings)
    section("Upstream compatibility drift (nurgling2 upstream remote)", upstream_warnings)
    section("Other warnings", other_warnings)
    section("Errors", errors)

    if limitations:
        print("-- Known v1 limitations --")
        for lim in limitations:
            print(f"  - {lim}")
        print()

    ok = not errors and not (args.strict and warnings)
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

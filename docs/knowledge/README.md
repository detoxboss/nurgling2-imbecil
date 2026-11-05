# Nurgling2 Knowledge Base — index

**Kind:** current. This is the compact-pointer layer, not the evidence layer. It exists so a task can
find the right reference in one short read instead of loading a full audit document. It does not
replace `docs/audit/**`, `docs/README.md`, or `tools/wiki-recipes/`; it indexes into them.

## What lives where

| Layer | Owns | Example |
|---|---|---|
| `docs/README.md` | General routing index for every tracked doc, by subject | "LP Assistant bot → `docs/lp-assistant-bot.md`" |
| `docs/audit/**` | Full narrative evidence: what was traced, compared, and concluded, per capability area | `docs/audit/capability-navigation.md` |
| **`docs/knowledge/capability-index.md`** (this layer) | One-line-status pointers into `docs/audit/**` findings, tagged with reachability and provenance | "`CAP-NAV-001`: ChunkNavExecutor replan bug — ACTIVE — see capability-navigation.md" |
| **`docs/knowledge/gameplay-index.md`** (this layer) | Pointers to gameplay facts: `recipes.bin` records, wiki pages+revisions, existing mechanic docs | "Oven/Bread quality formula → recipes.bin Oven record" |
| **`docs/knowledge/reuse-candidates.md`** (this layer) | Cross-system reuse opportunities with a stable lifecycle, not an append-only log | "`REUSE-002`: ChunkNav approach-point utility — PROPOSED" |
| **`docs/knowledge/observations.md`** (this layer) | Gameplay observations supplied by the user, not yet promoted to verified mechanics | "`OBS-001`: ..." |
| `tools/wiki-recipes/` | The actual gameplay-fact store (`recipes.bin`) and its revision-tracked update pipeline | `update_recipes.py --check` |

**Rule for this layer: index entries point, they never assert beyond a short status tag.** The full
conclusion, its evidence, and any later correction live in the owning audit doc or gameplay source.
If an index entry and its linked doc ever disagree, the doc wins and the index entry is stale — fix
the index, don't let it fork a competing claim. This mirrors the existing correction practice in
`docs/audit/methodology.md` (the Oven/Bread retraction, the session-1→session-2 upstream-ownership
correction): mark the old conclusion superseded, keep the reason, don't delete it.

## How automatic discovery works (two mechanisms, not one new system)

1. **Path-scoped**, for in-progress code work: `.claude/rules/*.md` files load automatically when a
   matching source file is touched. `chunknav-navigation.md`, `workstation-production.md`, and
   `bot-lifecycle.md` (added alongside this knowledge base) close the gap where those three capability
   areas previously had audit docs but no auto-loading rule.
2. **Task-level**, for unfamiliar tasks that haven't touched a file yet: `CLAUDE.md`'s "Start here"
   step points here before work begins, same as it already points to `docs/README.md`.

Neither mechanism preloads full documents — both point at a short file first (a rule, or this index),
and the agent decides whether to open the full linked doc.

## Record format

Every entry in `capability-index.md`, `gameplay-index.md`, `reuse-candidates.md`, and
`observations.md` is a short human-readable paragraph preceded by an HTML-comment metadata block:

```markdown
<!-- knowledge-record
id: CAP-NAV-001
type: capability
area: navigation
status: EVALUATED
reachability: ACTIVE
evidence_doc: docs/audit/capability-navigation.md
source_paths: src/nurgling/navigation/ChunkNavExecutor.java
verified_commit: 883d9d81976527d28d4271b5efcc6bc28a90e564
upstream_sha: unknown
-->
**CAP-NAV-001 — short title**
One or two sentences. Full detail stays in `evidence_doc`; don't restate it here.
```

This is the only structured part of the knowledge base — plain `key: value` lines inside an HTML
comment, no YAML, no database. `tools/knowledge/validate_knowledge.py` parses these blocks to check
links, required fields, duplicate/malformed IDs, and staleness (see below). The comment is invisible
when the file renders as prose, so the human-readable text next to it must stand on its own.

### Stable ID families

| Prefix | File | Meaning |
|---|---|---|
| `CAP-<AREA>-NNN` (`AREA` = `NAV`, `WSP`, `BOT`, …) | `capability-index.md` | A capability finding: one row per traced/evaluated behavior, not one row per source file |
| `GAME-NNN` | `gameplay-index.md` | A gameplay-fact pointer |
| `REUSE-NNN` | `reuse-candidates.md` | A cross-system reuse opportunity, with a lifecycle state (see that file) |
| `OBS-NNN` | `observations.md` | A user-supplied gameplay observation |

IDs are never reused or renumbered. A superseded record is marked `status`/`state: SUPERSEDED` (or the
most specific equivalent terminal state) in place — its history stays, the conclusion doesn't move.

### Required metadata by type

| Type | Required fields |
|---|---|
| `capability` | `id`, `type`, `area`, `status`, `reachability`, `evidence_doc`, `verified_commit` |
| `gameplay` | `id`, `type`, `topic`, `source`, `verification`, `last_checked` |
| `reuse` | `id`, `type`, `state`, `capability_source`, `candidate_consumers` |
| `observation` | `id`, `type`, `topic`, `status`, `date`, `evidence` |

`status`/`reachability` vocabularies are defined in `docs/audit/methodology.md` (extended for this
knowledge base — see its "Reachability and provenance" section). Don't invent a competing vocabulary
here.

## Provenance: local fork vs. upstream — never conflate the two

`verified_commit` is the commit at which **this fork's own source** was last confirmed to match the
record's claim (normally the commit that wrote the evidence-doc section being pointed to).
`upstream_sha` is, separately, the `aleksandrsvoboda/nurgling2` `upstream` remote commit a byte-level
comparison was last made against, if one was — **`unknown` when the originating audit session didn't
record one**, which is true for every capability record seeded in v1 (the existing capability-area
audits noted "byte-identical to upstream" qualitatively but never stamped the exact upstream SHA
compared against). Do not backfill a current `upstream/master` SHA into an old record to make this
field non-empty — that would misrepresent *when* the comparison happened. Record a real upstream SHA
only from here forward, when a comparison is actually made.

A matching `upstream_sha` only proves byte-identity **as of that comparison** — it is never proof that
local fork behavior has stayed unchanged since (the fork could have diverged after), and it is never
proof upstream hasn't moved since (upstream churns independently — `ChunkNav` had 18 commits in 90
days per `capability-navigation.md`). Both directions of drift are checked separately by the
validator (see below), and both are reported as distinct sections, never merged into one "is this
still valid" verdict.

## Reachability and reuse vocabulary (quick reference)

Full definitions: `docs/audit/methodology.md`. Quick reference only:

- **ACTIVE** — implemented and exercised by at least one real caller in the normal execution path.
- **CALLER-LIMITED** — implemented, reachable, but only from a specific, named caller — don't assume
  broader use.
- **UNUSED** — implemented, reachable, syntactically callable, but no caller currently invokes it.
- **UNREACHABLE** — implemented but dead: computed and never consumed, or gated behind a condition
  that cannot currently become true.
- **UNCONFIGURED** — a configuration option exists with no code path that currently sets or reads it
  meaningfully.
- **UNKNOWN** — reachability has not actually been traced; don't guess a level to fill the field.

`reuse-candidates.md` lifecycle states (`PROPOSED` / `INVESTIGATING` / `VALIDATED` / `ADOPTED` /
`REJECTED` / `SUPERSEDED`) are defined in that file's own header, not here.

## Adding or updating a record

1. Pick the right file by prefix family.
2. Copy an existing record's metadata block, give it the next unused ID in that family (check
   `tools/knowledge/validate_knowledge.py`'s duplicate-ID check rather than scanning by eye).
3. Link `evidence_doc`/`source`/`backlog_link`/`evidence` to something that actually exists — the
   validator checks this, but check it yourself first.
4. Write one or two plain sentences next to the block. If you're tempted to write a third paragraph,
   the content belongs in the owning audit doc, not here.
5. Run `python tools/knowledge/validate_knowledge.py` before committing.
6. If this record corrects an earlier one, mark the earlier one's terminal state and say why, in
   place — don't delete it and don't leave both asserting different things.

## Deferred (approved direction, not built in v1)

These are real, not abandoned — recorded here so a future session doesn't have to rediscover that
they were already considered and intentionally deferred:

- **Regular, targeted Ring of Brodgar revision checks** beyond manually running
  `update_recipes.py --check` — no scheduler, no new crawler. Reuse `tools/wiki-recipes/` as-is;
  revisit only if manual checks prove too infrequent in practice.
- **Automatically integrating wiki changes into `gameplay-index.md`** — today a human/Claude updates
  the index after noticing a `recipes.bin` change; no automatic sync.
- **More automated source-to-knowledge drift detection** than `validate_knowledge.py`'s v1 checks
  (e.g., semantic diffing of a traced method's body, not just "did the file change").
- **Better cross-system reuse discovery** than manually seeding `reuse-candidates.md` — no automated
  call-graph-based opportunity finder.
- **Capability coverage beyond navigation, workstation/production, and bot lifecycle** — inventory,
  UI, client/server assumptions remain unstarted per `docs/audit/README.md`; this knowledge base's
  structure is ready for them, but no new capability-area audit is part of this infrastructure work.
- **Automated post-upstream-sync maintenance** of `upstream_sha` fields — today, re-stamping after a
  sync is a manual step in `docs/fork-sync-guide.md`'s existing procedure, not automated here.
- **Linking modernization-backlog items to their eventual implementation commit** once a batch
  (Batch A/B/C) actually ships — `reuse-candidates.md`'s `backlog_link` field is ready for this, but
  no automation closes the loop yet.

## How to verify this layer actually works (manual checklist)

No vector DB, no retrieval service — "does discovery work" is checked by hand, per task:

1. Open/edit `src/nurgling/navigation/ChunkNavExecutor.java` in a fresh conversation → confirm
   `.claude/rules/chunknav-navigation.md` loads and its pointer resolves to a real
   `capability-index.md` record.
2. Same for `src/nurgling/actions/bots/SteelAction.java` → `workstation-production.md` rule.
3. Same for `src/nurgling/actions/bots/registry/BotRegistry.java` → `bot-lifecycle.md` rule.
4. Ask a gameplay-mechanic question (e.g., "where does the Oven/Bread quality formula live?") in a
   fresh conversation with no prior context → confirm it's answered from `gameplay-index.md` without
   loading all of `capability-workstation-production.md`.
5. Grep `capability-index.md` and `gameplay-index.md` for a topic keyword → confirm exactly one
   authoritative record comes back, not several contradicting ones.

Steps 1-3 require actually starting a fresh session to observe rule auto-loading — if you're reading
this inside a session that already has the file open, that's not a valid test of the auto-load path.

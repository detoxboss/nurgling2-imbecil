# Gameplay index

**Kind:** current (this index). Pointers to authoritative gameplay facts — `recipes.bin` records,
Ring of Brodgar wiki pages (by revision), and existing hand-written mechanic docs. **This file never
restates a formula or mechanic value** — doing so would create a second, driftable copy of a fact
`tools/wiki-recipes/` or an existing `current`-kind doc already owns. See `docs/knowledge/README.md`
for the record format.

If a mechanic isn't covered by `recipes.bin`'s schema (burn-time tables, some formulas — see
`tools/wiki-recipes/README.txt`'s documented gaps) and has no existing doc, that's a real gap: add a
record here pointing at the live wiki page by URL + revision, don't leave it undocumented just
because the automated pipeline doesn't reach it.

---

<!-- knowledge-record
id: GAME-001
type: gameplay
topic: Oven/Bread quality formula is filed under the Oven record, not Bread's
source: src/nurgling/craft/recipes.bin
verification: WIKI_DOCUMENTED
last_checked: 2026-11-05
related: docs/audit/methodology.md, docs/audit/capability-workstation-production.md
-->
**GAME-001 — Bread's quality formula lives under the Oven building's wiki/recipes.bin record, not Bread's own.**
Session 4 of the workstation-production audit re-checked this against the live wiki at an exact
revision match after a session-3 keyword search missed it. See `methodology.md`'s worked example for
why this matters generally, and don't repeat the single-keyword-search mistake for other
ingredient-vs-building formula lookups.

<!-- knowledge-record
id: GAME-002
type: gameplay
topic: FEP / food attribute-gain mechanics
source: docs/feps-system-reference.md
verification: current
last_checked: 2026-11-05
related: docs/equipment-tooltip-enhancements.md
-->
**GAME-002 — FEP/food/attribute-gain mechanics are already a dedicated current doc; don't re-derive here.**
Anything touching `OptimizeTableEating.java` or similar should read `docs/feps-system-reference.md`
directly, routed via the existing "Routing to canonical work areas" table in `CLAUDE.md`. This record
exists only so a gameplay-topic search finds the pointer.

<!-- knowledge-record
id: GAME-003
type: gameplay
topic: recipes.bin generation/revision-tracking pipeline
source: tools/wiki-recipes/README.txt
verification: VALIDATED
last_checked: 2026-11-05
related: docs/audit/capability-workstation-production.md
-->
**GAME-003 — `recipes.bin` is a maintainer-run, offline, revision-tracked snapshot of the Ring of Brodgar wiki; the client never calls the wiki at runtime.**
`python tools/wiki-recipes/update_recipes.py --check` reports what changed on the wiki since the last
accepted snapshot, by exact revision ID, without installing anything. This is the correct way to find
out "has the wiki changed" — see `docs/knowledge/README.md`'s deferred-items list for why this isn't
run on a schedule in v1.

<!-- knowledge-record
id: GAME-004
type: gameplay
topic: Bar of Steel quality formula — unresolved, not bound in recipes.bin
source: docs/audit/capability-workstation-production.md
verification: UNKNOWN
last_checked: 2026-11-05
related: docs/audit/modernization-backlog.md
-->
**GAME-004 — Whether Bar of Steel has a quality formula, and what it is, is unresolved — do not treat it as answered.**
The Ring of Brodgar page for Bar of Steel states a formula exists, but session 4 of the
workstation-production audit could not find it bound in `recipes.bin` under either its own record or
the Steel Crucible building record, and left open whether that's a parser gap or a wiki-formatting
issue. This record exists so that gap stays visible at the index level instead of being silently
treated as "same as the other kiln formulas" by a later task (e.g. steel fuel-quality work) that only
skims `GAME-001`'s oven/bread lesson and assumes the pattern generalizes.

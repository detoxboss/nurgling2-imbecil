# Cross-system reuse candidates

**Kind:** current (this registry). Tracks capability-reuse opportunities discovered during audits,
each with a stable ID and a lifecycle state — **not** an append-only event log. A superseded or
rejected candidate's record stays, with its reason, at its terminal state; it is not deleted and a
contradicting duplicate is not opened for the same opportunity. See `docs/knowledge/README.md` for
the record format.

## Lifecycle states

- **PROPOSED** — identified as a plausible reuse opportunity; feasibility not yet investigated.
- **INVESTIGATING** — actively being checked against the candidate consumer(s)' actual requirements.
- **VALIDATED** — reuse is confirmed feasible and correct, but not yet implemented.
- **ADOPTED** — implemented; `source_paths`/notes should point at the commit or PR that did it.
- **REJECTED** — investigated and found unsuitable; the reason is the valuable part, keep it.
- **SUPERSEDED** — replaced by a better-understood or differently-scoped candidate; link forward to
  the replacement ID.

Seeded with a small number of evidence-backed opportunities already surfaced by the closed audits —
not a speculative "could X reuse Y" entry for every bot. Add a new record only when an audit session
actually identifies a concrete opportunity, not preemptively.

---

<!-- knowledge-record
id: REUSE-001
type: reuse
state: PROPOSED
capability_source: Forager's independent route-recording/walking layer (docs/audit/capability-navigation.md §5)
source_paths: src/nurgling/actions/bots/Forager.java, src/nurgling/routes/ForagerRouteStore.java
candidate_consumers: other point-to-point bots that currently hand-roll waypoint/route logic instead of using a shared recording layer (no specific bot named yet)
backlog_link:
-->
**REUSE-001 — Forager's route-recording/walking layer as a general-purpose lightweight route primitive.**
Surfaced by the navigation capability-area's four-way comparison matrix (local pathfinding vs. global
vs. `ChunkNav` vs. Forager's own route-recording). Not yet investigated for reuse outside Forager
itself — feasibility and a concrete candidate consumer are both open.

<!-- knowledge-record
id: REUSE-002
type: reuse
state: PROPOSED
capability_source: ChunkNav portal/station approach-point handling (ChunkNavExecutor, ChunkNavManager)
source_paths: src/nurgling/navigation/ChunkNavExecutor.java
candidate_consumers: FollowRoute, StorageNav (the same two callers affected by CAP-NAV-001)
backlog_link: docs/audit/modernization-backlog.md
-->
**REUSE-002 — Consistent approach-point handling across ChunkNav's grid-coordinate callers.**
Named directly in the approved roadmap's "Batch B — Navigation consistency." Tied to `CAP-NAV-001` in
`capability-index.md`: fixing the replan bug and unifying approach-point handling for `FollowRoute`/
`StorageNav` are related but distinct — don't conflate fixing the immediate-fail bug with building
the shared utility.

<!-- knowledge-record
id: REUSE-003
type: reuse
state: INVESTIGATING
capability_source: TakeItemsFromContainer.minQuality / TakeItems2.minQuality lower-bound quality-floor gate, backed by FindQualityThreshold.QEPS
source_paths: src/nurgling/actions/TakeItemsFromContainer.java, src/nurgling/actions/TakeItems2.java, src/nurgling/actions/FindQualityThreshold.java
candidate_consumers: production bots lacking quality-aware fuel/ingredient selection (kiln/smelter/oven family, per the wiki-confirmed formulas in GAME-001)
backlog_link: docs/audit/modernization-backlog.md
-->
**REUSE-003 — Apply the existing `minQuality` lower-bound quality-floor pattern to the production-bot family that currently lacks it.**
The *absence* of quality-aware selection in the production bots is already VALIDATED in
`modernization-backlog.md` item 10; this record tracks the separate question of which already-existing
pattern is the right mechanism to close that gap.

**Correction (this session, independent verification):** an earlier draft of this record, and
`modernization-backlog.md` item 10's "Complexity" note, named `TakeItemsFromContainer.targetq` as the
reusable precedent. That is wrong: `targetq` is an *upper-bound cap* (rejects items *above* a quality
value — see `TakeItemsFromContainer.java` around its `item1.item).quality>targetq` check), not a
lower-bound floor. The mechanism this reuse opportunity actually needs is `minQuality` (present on
both `TakeItemsFromContainer` and `TakeItems2`, gated through `FindQualityThreshold.QEPS`), which is
already the field `Validator`/fuel-floor work would need to set. `Validator.fuel(...)` itself was also
an incorrect citation — it is existence-only (confirms a fuel zone/material exists), not
quality-aware at all. Do not assume `TakeItemsFromContainer` and `TakeItems2`'s same-named fields
behave identically without checking each call path; they are two distinct classes that happen to
share the `minQuality` name and `QEPS` gating convention, not one shared implementation.

# Capability index

**Kind:** current (this index); points into **recovery/current** evidence docs. Compact pointers into
`docs/audit/**` capability findings — see `docs/knowledge/README.md` for the record format,
required metadata, and the rule that this file states a status, never a conclusion. Read the linked
`evidence_doc` for the actual trace/comparison before relying on anything here.

Seeded from the three capability areas audited so far (navigation, workstation/production, bot
lifecycle). Not every finding in those audit docs has a row here — this is a seed set covering a
representative high-value finding per area per reachability category, not a full mirror. Add more
rows as needed; see `README.md`'s "Adding or updating a record."

**Before proposing a new mechanism for a finding below, also check
[`docs/knowledge/reuse-candidates.md`](reuse-candidates.md)** — a capability gap recorded here may
already have a tracked, evidence-backed reuse candidate (e.g. `CAP-WSP-002`'s fuel-quality-selection
gap ties to `REUSE-003`; `CAP-NAV-001`'s approach-point handling ties to `REUSE-002`). This is a
pointer, not a merge — the two files track different things (a capability's status vs. a candidate
fix's lifecycle state) and are not meant to be collapsed into one.

---

<!-- knowledge-record
id: CAP-NAV-001
type: capability
area: navigation
status: EVALUATED
reachability: ACTIVE
evidence_doc: docs/audit/capability-navigation.md
source_paths: src/nurgling/navigation/ChunkNavExecutor.java, src/nurgling/actions/FollowRoute.java, src/nurgling/actions/StorageNav.java
verified_commit: 883d9d81976527d28d4271b5efcc6bc28a90e564
upstream_sha: unknown
-->
**CAP-NAV-001 — `ChunkNavExecutor.replanAndContinue()` fails instead of replanning for grid-coordinate callers.**
High-severity reliability defect: on a replan, the method returns `Results.FAIL()` immediately when
`targetArea == null`, which is the case for its two real callers (`FollowRoute`, `StorageNav`) since
they plan by grid coordinate, not area. See `capability-navigation.md` §13 for the full trace.

<!-- knowledge-record
id: CAP-NAV-002
type: capability
area: navigation
status: EVALUATED
reachability: UNREACHABLE
evidence_doc: docs/audit/capability-navigation.md
source_paths: src/nurgling/navigation/ChunkNavGraph.java, src/nurgling/navigation/ChunkNavData.java
verified_commit: 883d9d81976527d28d4271b5efcc6bc28a90e564
upstream_sha: unknown
-->
**CAP-NAV-002 — ChunkNav confidence/staleness-penalty mechanism is computed but never consumed.**
A confidence/staleness-penalty value is calculated during planning but the live planner never reads
it back — confirmed dead by reading the actual consuming code path (there isn't one). See
`capability-navigation.md` §13.

<!-- knowledge-record
id: CAP-WSP-001
type: capability
area: workstation-production
status: EVALUATED
reachability: ACTIVE
evidence_doc: docs/audit/capability-workstation-production.md
source_paths: src/nurgling/actions/bots/BakerAction.java
verified_commit: 883d9d81976527d28d4271b5efcc6bc28a90e564
upstream_sha: unknown
-->
**CAP-WSP-001 — Baker bot's fire-out-vs-item-doneness completion check is a confirmed, documented bug.**
Session 4 re-verified against the live Ring of Brodgar page (exact revision match) that the ash-burning
failure mode is real, strengthening rather than weakening this finding. See
`capability-workstation-production.md` Phase 3 ("Baker bot investigation").

<!-- knowledge-record
id: CAP-WSP-002
type: capability
area: workstation-production
status: EVALUATED
reachability: ACTIVE
evidence_doc: docs/audit/capability-workstation-production.md
source_paths: src/nurgling/actions/bots/SteelAction.java
verified_commit: 883d9d81976527d28d4271b5efcc6bc28a90e564
upstream_sha: unknown
-->
**CAP-WSP-002 — `SteelAction` is a single-shot bot structurally identical to `TarkilnAction`; intent PLAUSIBLE, not CONFIRMED.**
Two independent occurrences of the same shape raise confidence it's deliberate, but neither session
confirmed the design intent directly (vs. an unfinished fuel-loop conversion). See
`capability-workstation-production.md` and `modernization-backlog.md` item 8.

<!-- knowledge-record
id: CAP-BOT-001
type: capability
area: bot-lifecycle
status: EVALUATED
reachability: ACTIVE
evidence_doc: docs/audit/capability-bot-lifecycle.md
source_paths: src/nurgling/actions/Results.java
verified_commit: 21c5ea13c232f1983968d0d3ed76b0a4969d6acc
upstream_sha: unknown
-->
**CAP-BOT-001 — `Results.ERROR`'s unconditional `NUtils.getGameUI()` dereference can swallow the original failure behind an NPE.**
Confirmed call-pattern (nullable accessor, unconditional dereference, 196 non-null call sites
including inside `InterruptedException` catch blocks); no reproduced crash. See
`capability-bot-lifecycle.md` "Session 2: `Results.ERROR`'s null-dereference hypothesis" and
`modernization-backlog.md` item 1.

<!-- knowledge-record
id: CAP-BOT-002
type: capability
area: bot-lifecycle
status: TRACED
reachability: ACTIVE
evidence_doc: docs/audit/capability-bot-lifecycle.md
source_paths: src/nurgling/widgets/BotsInterruptWidget.java
verified_commit: 21c5ea13c232f1983968d0d3ed76b0a4969d6acc
upstream_sha: unknown
-->
**CAP-BOT-002 — `BotsInterruptWidget`'s `MAX_BOTS = 9` tracked-bot cap has no overflow handling.**
Confirmed by reading the code (`addObserve` caps at 9); not reproduced live with 10+ concurrent bots.
See `capability-bot-lifecycle.md` and `modernization-backlog.md` item 2's sibling finding.

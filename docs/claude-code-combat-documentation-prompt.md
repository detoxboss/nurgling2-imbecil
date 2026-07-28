# Prompt for Claude Code: Build the Canonical Nurgling Combat Documentation

You are working inside the completed Nurgling2 repository where you implemented the Nurgling-native Haven & Hearth combat reactor.

This is a documentation and verification task only. Do not recap the prior Claude session from memory. Reconstruct the truth from the repository, Git evidence, the implementation plan, the port brief, the historical R3 documents, and actual verification results.

## Objective

Create a compact, modular, linked, version-controlled source-of-truth system for the Nurgling combat reactor. It must let a future Claude Code session quickly determine:

1. what is known about the relevant Haven & Hearth combat mechanics;
2. what behavior the user approved;
3. what the current Java implementation actually does;
4. which client state is authoritative, unknown, or unavailable;
5. where each behavior is implemented;
6. why lasting architecture decisions were made;
7. what changed during the initial port;
8. what has and has not been verified;
9. what remains unresolved;
10. how future configurable combat rules should be designed without pretending proposed features already exist.

The documentation must accurately describe incomplete or noncompliant behavior if found. It is a truth record, not a claim that the port is perfect.

Treat this as a small on-demand knowledge library or knowledge map. The documents should be strongly cross-linked and collectively complete, while each file owns only one narrow category of truth. A future Claude session should begin at the compact index, follow only the links relevant to its current task, and recover the same evidence-grounded understanding without loading the entire combat history into every conversation.

This cannot literally guarantee that an AI never hallucinates, but it must make unsupported assumptions easy to detect: every important claim needs a canonical owner, evidence classification, stable ID, and route back to implementation, requirement, mechanics evidence, or an explicit unresolved item.

## Inputs to inspect completely

Read these before authoring:

- `docs/haven-combat-reactor-nurgling-port-brief.md`
- `docs/combat-system-spec-r3.md`
- `docs/combat-interface-reference-r3.md`
- `docs/automation-requirements-r3.md`
- `docs/implementation-decisions-r3.md`
- the implementation plan I provide with this request, named `binary-chasing-dijkstra.md` unless I give you a different path;
- the complete current combat implementation and every framework class it calls;
- relevant Git history, current `HEAD`, status, tracked diff, and untracked files;
- the existing root `CLAUDE.md`, `.claude/`, build file, and repository documentation conventions.

If the plan file is not accessible, stop before writing and ask me for its exact path. Do not reconstruct it from memory.

At minimum, trace these known implementation areas, then follow all callers and dependencies you discover:

- `src/nurgling/combat/*.java`
- `src/nurgling/widgets/NCombatReactor.java`
- `src/nurgling/widgets/nsettings/CombatReactorSettings.java`
- `src/nurgling/NGameUI.java`
- `src/nurgling/NConfig.java`
- `src/nurgling/widgets/NSettingsWindow.java`
- `src/nurgling/plugins/CombatEvents.java`
- `src/haven/NFightsess.java`
- `src/haven/Fightview.java`
- `src/haven/Fightsess.java`
- `src/haven/FightWnd.java`
- `src/nurgling/NFightWnd.java`
- opening/buff, relation, action-slot, cooldown, keybinding, settings-persistence, and UI-thread code used by the reactor;
- `build.xml`.

Do not limit the audit to this list if source references lead elsewhere.

## Evidence rules

Keep these separate:

- game-mechanics truth;
- user-approved behavior;
- planned behavior;
- current source behavior;
- passing test/build evidence;
- manual observations;
- historical AHK behavior;
- recommendations;
- unresolved questions.

Use this authority model:

1. Current source directly establishes only what the implementation does.
2. Passing tests/builds establish only what they actually exercise.
3. Explicit user-approved requirements establish intended behavior.
4. Verified mechanics constrain valid behavior.
5. Accepted ADRs explain architectural decisions.
6. Plans and summaries establish intent, not completed implementation.
7. Historical AHK behavior is not automatically a Nurgling requirement.
8. Inference must remain labeled as inference.

When code and an approved requirement disagree, record noncompliance. Never rewrite the requirement to make the code look correct.

Never translate missing state into `0`, `false`, or “not detected.” Distinguish:

- `KNOWN`: an authoritative current value exists;
- `UNKNOWN`: a source exists, but its current value is not established;
- `UNAVAILABLE`: the client does not expose the value reliably.

Do not claim a method, test, hook, acknowledgement, queue state, cooldown rule, or event exists until you verify it in current source.

## Git and working-tree handling

Before writing, capture:

```bash
git rev-parse HEAD
git log -1 --format=fuller
git status --short --untracked-files=all
git diff --stat
git diff --name-status
```

The port may still be uncommitted. If so:

- identify the implementation as `HEAD <hash> + uncommitted working tree`;
- do not invent a post-port commit;
- include all relevant untracked files in the audit;
- record `Commit: uncommitted (baseline HEAD <hash>)` in the initial changelog;
- update to an exact commit later, after one exists.

Some tracked files may show large diffs caused mostly by CRLF/LF changes. Diagnose this with suitable Git options such as `--ignore-space-at-eol` or `--ignore-cr-at-eol`. Do not normalize or rewrite line endings during this task, and do not treat line-ending noise as behavioral work.

## Required repository structure

Create:

```text
.claude/
  rules/
    combat-automation.md
docs/
  combat/
    README.md
    mechanics.md
    behavior-contract.md
    observable-state.md
    code-map.md
    rule-system.md
    evidence.md
    verification.md
    unresolved.md
    changes/
      CHANGELOG.md
    decisions/
      README.md
      ADR-0001-<verified-decision>.md
      ADR-0002-<verified-decision>.md
      ...
```

Use normal relative Markdown links, not filesystem symlinks. This repository is used on Windows, where symlinks add avoidable friction.

Here, “linked” means a navigable set of small references that behave like a conceptual knowledge graph. It does not mean creating operating-system symlinks.

Do not import the detailed combat documents into root `CLAUDE.md`; `@path` imports load their content into context and defeat the token-saving design. Claude Code discovers `.claude/rules/*.md` itself. Preserve the existing `CLAUDE.md` unless a small, clearly necessary addition is required.

## Token-efficient loading design

`.claude/rules/combat-automation.md` must use verified path-scoped YAML frontmatter. Include exact globs covering:

- the combat package;
- reactor widget and settings files;
- haven/nurgling integration files modified for the reactor;
- combat tests if they exist or are later added;
- `docs/combat/**/*.md`.

Keep this rule concise. It should instruct future Claude sessions to:

- read `docs/combat/README.md` before combat-related work;
- follow the authority and unknown-state rules;
- preserve relation/target scoping and other verified safety invariants;
- run the documented verification gates;
- perform the documentation-impact check after any combat change.

Refer to the README using a literal code-formatted path, not an `@` import.

Detailed documents load on demand through links from `docs/combat/README.md`. Do not duplicate full tables or explanations across files.

## Canonical file ownership

### `docs/combat/README.md`

This is the short discovery index, not a giant summary.

Include:

- a 10–20 line current-system overview;
- current implementation identity (`commit` or `HEAD + worktree`);
- a “read this for…” table linking every canonical file;
- authority/conflict rules;
- minimum reading routes for mechanics, behavior changes, implementation changes, debugging, and rule-builder work;
- document revision/status table;
- last cross-document audit date.

Target roughly 80–150 lines.

### `mechanics.md`

Own only mechanics truth that constrains the reactor. Migrate relevant material from the R3 mechanics document without AHK implementation details.

Allowed labels:

- `[Verified game mechanic]`
- `[Community-documented mechanic]`
- `[Historically verified; current status unresolved]`
- `[Unresolved question]`

Assign stable IDs such as `MEC-IP-001`, `MEC-OPEN-001`, and `MEC-CD-001`. Link to evidence IDs in `evidence.md`. Preserve uncertainty from R3; do not upgrade old or community claims.

### `behavior-contract.md`

Own intended automation behavior independent of Java.

Include stable IDs for:

- enable/disable and lifecycle;
- combat-presence and current-relation scoping;
- defensive selection, priority, and tie behavior;
- offensive recommendation truth table;
- manual attack trigger;
- queue, cooldown, cancellation, revalidation, stale-action, and confirmation policies;
- fail-closed handling;
- non-goals;
- known current noncompliance linked to verification entries.

Allowed labels:

- `[Approved requirement]`
- `[Derived requirement]`
- `[Proposed behavior]`
- `[Unresolved product decision]`

Do not turn recommendations from the old audit or port brief into approved requirements unless approval is evidenced.

### `observable-state.md`

Own what Nurgling/hafen can authoritatively observe.

For every value, record:

| State | Type/unit/range | Scope | Exact source | Update trigger | Unknown/unavailable semantics | Consumers |
| --- | --- | --- | --- | --- | --- | --- |

Cover at least:

- combat presence;
- current relation and target identity;
- player and opponent initiative/IP;
- player and opponent openings by color;
- action/deck slots;
- action availability;
- global and per-action cooldown information;
- held/selected/queued/used action state;
- execution acknowledgement or closest available signal;
- player soft/hard HP;
- opponent soft/hard HP;
- any value proposed for the future rule builder.

Verify actual units and semantics. Openings are not “counts” unless source proves count semantics. Mark unsupported opponent HP fields `UNAVAILABLE` rather than designing UI around imaginary data.

### `code-map.md`

Own current implementation topology only.

Use:

| Component | Exact path | Class/method | Responsibility | Reads | Sends/writes | Contract IDs | Verification |
| --- | --- | --- | --- | --- | --- | --- | --- |

Also document:

- widget/controller lifecycle;
- snapshot acquisition;
- change detection and tick frequency;
- decision flow;
- defense priority and tie flow;
- manual attack flow;
- action request/release path down to actual `wdgmsg` behavior;
- action acceptance/sent/queued/executed semantics actually implemented;
- event subscription and cleanup;
- relation change and combat-end cancellation;
- threading/UI-thread assumptions;
- settings and keybinding persistence;
- diagnostics rendering;
- current limitations and source/plan variances.

Use class and method names plus paths, not unstable line-number citations.

### `rule-system.md`

Separate these explicitly:

1. what is implemented now;
2. what the user has approved for a future configurable rule system;
3. recommended first schema;
4. unapproved ideas;
5. operands blocked by unavailable client state.

The recommended flat first model is:

```text
Rule
  id
  enabled
  priority
  action
  matchMode: ALL | ANY
  conditions[]

Condition
  operand
  operator
  numericValue
```

Use `ALL` and `ANY`. Do not offer both `OR` and `ANY`; they mean the same thing for a flat group. Do not add nested Boolean groups until there is a real need for expressions such as `(A AND B) OR C`.

For every proposed operand, define type, unit, valid range, target/relation scope, update timing, and unknown-value policy. Operators for numeric operands:

```text
==  !=  <  <=  >  >=
```

Document evaluation timing, priority/conflict resolution, first-match versus multi-fire behavior, cooldown/queue interaction, validation, serialization versioning/migration, duplicate conditions, contradictory conditions, UI add/remove/reorder behavior, and safe defaults. Do not claim this system exists if it does not.

### `evidence.md`

Create a compact evidence register so other documents can cite stable source IDs rather than duplicate source descriptions.

Include:

| Evidence ID | Kind | Source/path/URL | Version/date/commit | Supports | Limitations |
| --- | --- | --- | --- | --- | --- |

Register:

- each R3 document;
- the port brief;
- the implementation plan;
- relevant current source files or tightly related groups;
- Git baseline/worktree;
- build output;
- any actual automated or manual test evidence;
- external mechanics sources already preserved in R3.

Do not cite the implementation plan as proof that code exists. Do not cite successful compilation as proof of runtime behavior.

### `verification.md`

Create a traceability matrix:

| ID | Claim/contract | Evidence | Automated test | Manual check | Latest result | Implementation identity |
| --- | --- | --- | --- | --- | --- | --- |

Use explicit statuses: `PASS`, `FAIL`, `NOT RUN`, `NOT IMPLEMENTED`, `MANUAL PENDING`, or `NOT TESTABLE WITH CURRENT HARNESS`.

At minimum cover:

- the complete attack truth table;
- all defense mappings and ties;
- unknown/unavailable fail-closed cases;
- initiative/IP gating;
- relation switch and combat end;
- disable/safe-stop;
- deck/action missing;
- cooldown rejection;
- held-action release;
- manual hotkey behavior;
- execution confirmation semantics;
- settings persistence;
- normal non-reactor combat input after shared-path changes;
- build result.

Matching code is not a passing test. Do not state that a case was manually verified unless a human actually performed it and reported the result.

### `unresolved.md`

Give every unresolved item a stable ID and include:

- question;
- why it matters;
- present evidence;
- what would resolve it;
- affected mechanics/contracts/code;
- status/owner;
- opened and last-reviewed dates.

Keep resolved entries as short tombstones linking to the final canonical owner; do not silently delete history.

### `changes/CHANGELOG.md`

Create an append-only initial entry for the native port:

```markdown
## YYYY-MM-DD — Initial Nurgling-native combat reactor port

- Commit: `uncommitted (baseline HEAD <hash>)` or exact commit
- Request/source:
- Why:
- Changed behavior: contract IDs
- Code files: exact paths
- Documentation files: exact paths
- Plan versus implementation variances:
- Verification performed: commands/results
- Manual verification still required:
- Known limits/follow-ups: IDs or `None`
- Implemented by:
- Documentation reconstructed by:
```

Derive what/where/why from evidence. Do not merely paste the implementation plan.

### `decisions/`

Create ADRs only for lasting decisions supported by evidence. Likely candidates to verify include:

- direct client state instead of screen/pixel recognition;
- tick/diff observation model;
- relation-scoped state and fail-closed unknown handling;
- dispatch through the normal `Fightsess`/`NFightsess` action path;
- defense-before-manual-attack priority;
- flat `ALL`/`ANY` future rule groups.

Do not create an accepted ADR for a proposed future choice unless the user approved it.

ADR format:

```markdown
# ADR-NNNN: Title

- Status: Proposed | Accepted | Superseded
- Date:
- Decision owners:
- Related contract IDs:
- Related implementation:
- Related evidence:

## Context
## Decision
## Alternatives considered
## Consequences
## Verification
## Supersedes / superseded by
```

ADRs preserve history. Current truth remains in the topic documents.

## Metadata and stable links

Each canonical file must begin with compact YAML metadata:

```yaml
---
doc_id: combat-<topic>
revision: 1
status: current
last_verified: YYYY-MM-DD
verified_against: "<exact commit or HEAD + uncommitted worktree>"
canonical_for:
  - "<one or more non-overlapping ownership statements>"
---
```

Rules:

- one canonical owner per fact category;
- relative Markdown links only;
- stable claim/contract/evidence/unresolved IDs;
- no line-number citations;
- no duplicated full truth tables;
- do not update a document’s `last_verified` unless its contents were actually rechecked;
- use the local date and record the timezone where relevant.

## Required implementation audit

Before drafting, trace the real runtime flow from client state to action message. Compare the implementation with:

1. the approved behavior in the port brief/R3 requirements;
2. the implementation plan;
3. the current source;
4. build/test/manual evidence.

Classify every mismatch as one of:

- planned but not implemented;
- implemented differently but behaviorally equivalent;
- approved deviation;
- implementation defect/noncompliance;
- documentation-only discrepancy;
- unresolved due to missing evidence.

Pay special attention to:

- whether snapshots really distinguish all declared opening states;
- whether player IP and opponent IP are correctly named and relation-scoped;
- whether both sides’ openings are actually acquired;
- whether the state adapter resolves current deck slots dynamically;
- whether action availability and cooldown readiness are correctly derived;
- whether defense is automatic and attack is manual-only as approved;
- whether green/blue tied defenses are truly queued/revalidated as planned;
- whether priority and serialization can drop or overwrite actions;
- whether “accepted,” “sent,” “queued,” and “executed” are real distinct states or only labels;
- whether `CombatEvents` provides sufficient execution confirmation;
- whether release/fenced `rel` behavior matches normal combat input;
- whether disabling, combat end, widget destruction, or relation change cancels and releases safely;
- whether the global hotkey is collision-safe and settings persistence works;
- whether normal keyboard combat behavior changed;
- whether diagnostics report facts or misleading approximations;
- whether the implementation plan’s TODO/extension points actually exist.

Do not fix implementation problems during this task. Record them with IDs and evidence.

## Verification to run

Run the repository’s documented build command, expected to be:

```bash
ant jar
```

First inspect `CLAUDE.md` and `build.xml` to confirm it. Run any existing focused tests if a real test harness exists. Do not create tests or production changes in this documentation-only task.

Capture:

- exact commands;
- exit status;
- relevant result;
- date;
- implementation identity.

Do not perform or claim in-game manual checks yourself. Mark them pending unless I have provided observed results.

## Cross-document and drift audit

Before finishing:

1. verify every relative Markdown link resolves;
2. verify every exact source path in `code-map.md` exists;
3. detect duplicate stable IDs;
4. detect references to missing IDs;
5. confirm every canonical file has valid metadata;
6. confirm each fact category has only one canonical owner;
7. compare attack/defense rules across mechanics, behavior, code map, and verification;
8. check current-behavior claims against source again;
9. check every verification claim against actual evidence;
10. search for accidental upgrades from proposed/community/historical to approved/verified;
11. search for `UNKNOWN` or `UNAVAILABLE` values represented as zero/false;
12. ensure the root instructions and combat rule do not eagerly import the large documentation set;
13. ensure no unrelated files or line endings changed.

If practical, use a temporary script or shell commands for this audit, but do not add a permanent maintenance script unless I separately approve it.

## Documentation maintenance protocol to encode

The path-scoped rule must require this after every future combat-related task:

1. inspect the current canonical docs and source;
2. identify affected contract/claim IDs;
3. implement and verify the requested change;
4. update only affected canonical owners;
5. update `code-map.md` for topology/lifecycle changes;
6. update `behavior-contract.md` only after explicit behavior approval;
7. update `mechanics.md` only with adequate evidence;
8. add/supersede an ADR for lasting decisions;
9. append one changelog entry;
10. update the verification matrix;
11. review unresolved items;
12. run link/path/ID/contradiction checks;
13. update revisions and verification dates only where rechecked;
14. explicitly report `No canonical documentation update required` when none is affected.

## Scope restrictions

- Do not modify Java, tests, resources, build logic, settings behavior, or unrelated documentation.
- Do not commit, push, reset, clean, or discard changes.
- Do not normalize line endings.
- Preserve the historical R3 files and port brief unchanged.
- Do not create real filesystem symlinks.
- Do not make the root `CLAUDE.md` large.
- Do not hide defects or incomplete work.
- Do not copy large blocks from the R3 files when a stable ID and link is enough.

## Final response

After writing and auditing the files, report:

1. all files created or modified;
2. the exact implementation identity documented;
3. build/test results;
4. important plan-versus-code variances or noncompliance IDs;
5. manual verification still required;
6. any unresolved item that blocks truthful documentation;
7. confirmation that links, paths, IDs, and contradictions were checked.

Do the repository inspection and documentation work yourself. Do not give me a proposed documentation outline and ask me to execute it.

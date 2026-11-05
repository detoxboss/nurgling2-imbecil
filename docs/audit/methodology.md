# Audit methodology: coverage levels

**Kind:** current. Governs how every capability area in this audit tree (not just workstation &
production automation) tracks how thoroughly a subsystem has actually been examined. Introduced after
an explicit correction: mapping a subsystem's architecture is not the same as having audited it for
outdated, inefficient, unreliable, duplicated, inconsistent, or primitive implementations — the
original goal of this whole audit. Apply this methodology to every future capability area (navigation,
inventory, bots, UI, and any other not yet started — see `README.md`'s Status section).

## The four levels

A subsystem or individual implementation is tracked at exactly one of these four levels at any time.
Levels are not cumulative badges collected once and kept forever — a subsequent session can discover
evidence that downgrades an earlier level (see the Oven/Bread example below, where a SESSION-3
`EVALUATED` claim had to be corrected after SESSION-4 re-checked it against the primary source).

1. **DISCOVERED** — the implementation(s) and their direct dependencies have been identified and
   located (file paths, class names, call sites), but their actual behavior has not been read in
   detail. A grep hit or a cross-reference from another file counts as DISCOVERED, nothing more.
2. **TRACED** — the actual execution path, state transitions, interaction sequence, and failure
   behavior have been read and verified from the implementation's own source (or, where source reading
   isn't possible, from direct runtime observation). This answers "what does it actually do," not yet
   "is it good."
3. **EVALUATED** — the traced behavior has been compared against alternative implementations in the
   same codebase, reusable utilities that could apply, or known external documentation (wiki,
   changelogs). This answers "how does it compare," and produces the comparison matrices and
   modernization-opportunity candidates this audit exists to find.
4. **VALIDATED** — the EVALUATED conclusion is backed by durable evidence: a specific code citation,
   a test, direct runtime observation, or a dated, sourced external document — **and every remaining
   uncertainty in that evidence is written down explicitly**, not glossed over. A VALIDATED entry can
   still carry open questions; what makes it VALIDATED is that those questions are named, not that
   there are none. `WIKI_DOCUMENTED` (sourced to a specific page/revision but not independently
   reproduced) and `VERIFIED_RUNTIME` (reproduced live) are both sub-tags usable at this level — see
   `capability-workstation-production.md` for where that classification scheme originated.

**Critical rule, the reason this document exists:** finishing DISCOVERED (or even TRACED) for every
file in a capability area's main architecture does **not** mean the capability area is audited.
Mapping the shape of a system is necessary but not sufficient — EVALUATED and VALIDATED are where the
actual modernization findings live, and they require deliberately asking "is there a better
implementation elsewhere, is this duplicated, is this assumption too restrictive" rather than stopping
once the architecture is understood.

## Tracking granularity

Track coverage **per subsystem and per individual implementation**, not one level for an entire
capability area. "Workstation & production automation: TRACED" is too coarse to be useful — the
Baker/Bricks/BlockAsh/BoneAsh loop family might be EVALUATED while `FriedFish`'s roastspit mechanism is
only TRACED and `SmokeringBot`'s equipped-item mechanism is only DISCOVERED. Use a table (see below for
the retrofit of the workstation & production area) listing each implementation with its own level, a
one-line justification, and a pointer to where the evidence lives.

## Worked example of why this matters: the Oven/Bread formula

Session 3 of the workstation & production capability area searched the fork's bundled wiki-recipe
database (`recipes.bin`) for "Bread" and found no quality formula attached to that record, concluding
"**bread has no fuel-quality term**, unlike kiln-fired goods" — and used that conclusion to narrow a
backlog recommendation (don't add fuel-quality filtering to the Baker bot). That conclusion was
EVALUATED-looking (it compared Bread against the kiln-family formula) but was never actually
VALIDATED — no one checked whether the formula might be recorded under a *different* record than the
product's own name.

Session 4 checked, found the formula **is** present in `recipes.bin` — attached to the **Oven**
building's own record, not Bread's — exactly matching the live Ring of Brodgar page's formula, and
retracted the session-3 conclusion. **The lesson: an EVALUATED-sounding conclusion drawn from a single
keyword search against one data source, without checking whether that source's own schema might file
the fact under a different key, is not VALIDATED — and should not be used to narrow a recommendation
until it is.** See `capability-workstation-production.md`'s "Session 4" section for the full
correction. This is preserved here, not deleted, because the failure mode (not the specific fact) is
the reusable lesson for every future capability audit.

## Coverage table: workstation & production automation (retrofit, sessions 1-4)

This retrofits sessions 1-4 of `capability-workstation-production.md` against the four levels. It is a
map into that document, not a restatement of its findings — read the linked sections for the actual
evidence.

| Subsystem / implementation | Level | Why | Evidence location |
|---|---|---|---|
| Fuel-loop bots (`BakerAction`/`BricksAction`/`BlockAshAction`/`BoneAshAction`) | **EVALUATED** | Traced loop shape, compared against each other (near-identical) and against `SmelterAction` (more sophisticated); Baker's specific failure mode compared against a documented sibling bug | Session 1 Phase 1-3 |
| `SmelterAction` | **EVALUATED** | Traced; established as the most sophisticated bot in the family, used as the comparison baseline for the others | Session 1 Phase 3 comparison matrix |
| `SteelAction` | **EVALUATED**, intent **PLAUSIBLE not CONFIRMED** | Traced (single-shot); compared against the loop-based bots (session 1) and against `TarkilnAction` (session 3), which shares its shape — two independent occurrences raises confidence but a direct confirmation of intent is still missing | Sessions 1 & 3; backlog item 8 |
| `LyeBoiler` | **EVALUATED** | Traced; its `fuelmod` potency multiplier compared against every other bot (unique to it) | Session 1 Phase 3 |
| Generic crafting-window path (`Craft.java`/`UseWorkStation`/`PrepareWorkStation`/`PrepareCauldron`) | **EVALUATED** | Traced in full; compared against the fuel-loop path's completion detection (found more robust: progress bar + error signal vs. fire-out) | Session 2 Phase 2 |
| `AutocraftBot` | **EVALUATED** | Traced in full this session; compared directly against `Craft.java` — confirmed to be a thin preset-configuration shell with zero duplicated execution logic, not a competing implementation | Session 4 §3 (Autocrafting comparative matrix) |
| `GelatinAction` | **EVALUATED** | Traced; shown to compose both the fuel-loop and generic-craft paths at one call site, the first confirmed example of composition rather than exclusive choice | Session 3 §2 |
| `TarkilnAction` | **EVALUATED** | Traced; compared against `SteelAction`, found structurally identical | Session 3 §2 |
| `FriedFish` | **EVALUATED** | Traced; identified as a third, independent completion-detection mechanism (sprite-overlay content), not comparable 1:1 to the other two paths since nothing else uses it — comparison is "this exists and doesn't overlap," which is itself the finding | Session 3 §2 |
| `SmokeringBot` | **TRACED** only | Read in full, confirmed tangential (equipped-item production, no station gob) — deliberately not evaluated further against workstation alternatives since it isn't one | Session 3 §2 |
| Loom/churn/spinning-wheel/ropewalk/potter's-wheel/meat-grinder | **DISCOVERED** only | Their `workstation_map` registry entries are traced, but no dedicated bot exists to evaluate, and the generic-craft path's adequacy *for these specific stations* (as opposed to the stations session 2/3 actually exercised) has not been separately checked | Session 2 Phase 1, Session 3 §2; backlog item 11 |
| Quern | **DISCOVERED** only | Hitbox confirmed; zero automation of any kind to trace or evaluate | Session 2 Phase 1; backlog item 9 |
| `recipes.bin` (as a data source, not as code) | **VALIDATED**, with explicit caveats | Generation pipeline traced (`tools/wiki-recipes/update_recipes.py`/`compile_recipes.py`/schema doc); representative entries (Kiln, Oven, Bar of Steel, Steel Crucible, Crucible) compared directly against their live wiki pages; agreements and one unresolved gap (Bar of Steel's product-quality formula) both recorded | Session 4 §1-2 |
| Quality-aware fuel/ingredient selection | **VALIDATED** (as an absence) | Confirmed present-pattern-elsewhere (`MasterMiner`, `TakeItemsFromContainer`, breeding bots) but absent from every production bot; cross-checked against wiki-documented formulas showing where it would matter (kiln/smelter/oven family, confirmed via Session 4's correction) and where the evidence doesn't support it (unresolved for steel specifically) | Session 3 §4, Session 4 §2; backlog item 10 |
| `Validator.fuel(...)` pre-flight check | **DISCOVERED** only | Seen gating several bots; never read | Session 1 "Not covered" |
| `LightGob` vs `LightObject` relationship | **DISCOVERED** only | `LightObject` read in full; whether `LightGob` is a wrapper or separate implementation never checked | Sessions 1-3 "Not covered" |
| Polling/scanning overhead | **Not evaluated at all** | No profiling performed in any session | Sessions 1-3 "Not covered" |

## How to apply this to future capability areas

For navigation, inventory, bots (general), UI, or any other area not yet started:

1. Do breadth-first DISCOVERY first (as this audit already did for workstation & production) — but
   explicitly label it as DISCOVERED only, and do not describe the capability area as "audited" from
   that alone.
2. Pick specific implementations to TRACE based on what the user flagged, what looks highest-risk, or
   what's most load-bearing — not everything at once.
3. For anything traced, deliberately ask the EVALUATED-stage questions before moving on: is there
   already a better implementation elsewhere in this codebase; is this behavior duplicated; are its
   assumptions unnecessarily restrictive; are its edge cases handled; could it use fewer operations or
   more reliable state observations; are existing shared utilities being ignored. Don't stop at "I
   understand what this does."
4. Before relying on any EVALUATED conclusion that came from a single data source or a single keyword
   search, check whether that source's own schema/generation process could file the relevant fact
   somewhere other than where you looked (the Oven/Bread lesson). This is cheap insurance against a
   false EVALUATED-but-wrong conclusion reaching VALIDATED.
5. Keep the per-subsystem table, not a single area-wide level. Update it every session.

## Reachability vocabulary (orthogonal to the four coverage levels)

A coverage level (DISCOVERED/TRACED/EVALUATED/VALIDATED) says how thoroughly something was examined.
It says nothing about whether that something actually runs. Track reachability as a separate tag,
used by `docs/knowledge/capability-index.md`'s `reachability` field and by any audit doc that wants to
state it precisely instead of writing "dead code" or "active" informally:

- **ACTIVE** — implemented and exercised by at least one real caller in the normal execution path.
- **CALLER-LIMITED** — implemented and reachable, but only from a specific, named caller (or small set
  of callers) — don't generalize that to "used throughout" without checking each additional caller.
- **UNUSED** — implemented, reachable, syntactically callable, but no caller currently invokes it.
- **UNREACHABLE** — implemented but dead: computed and never consumed (e.g. `ChunkNav`'s confidence/
  staleness-penalty mechanism — `capability-navigation.md` §13), or gated behind a condition that
  cannot currently become true.
- **UNCONFIGURED** — a configuration option exists with no code path that currently sets or reads it
  meaningfully.
- **UNKNOWN** — reachability has not actually been traced. Use this instead of guessing a level to
  fill the field; an honest UNKNOWN is more useful than a fabricated ACTIVE.

Do not label something UNUSED/UNREACHABLE/UNCONFIGURED from reading the implementation alone — that
only tells you it *could* be dead. Confirm via caller analysis (who calls this, and does that call
site's own code path actually execute) before asserting a negative reachability claim, same as any
other EVALUATED/VALIDATED-stage conclusion in this document.

## Provenance: local fork verification vs. upstream compatibility verification

These are two different claims and must never be collapsed into one "is this still valid" verdict:

- **Local fork source verification** — a claim about what *this fork's own current source* does,
  verified by reading or running it. Stamped as `verified_commit`: the fork commit at which the claim
  was confirmed (normally the commit that wrote the evidence-doc section making the claim). A later
  commit touching the same file, or an uncommitted working-tree change to it, means the claim needs
  re-checking — it does not mean the claim is wrong, only unverified as of now.
- **Upstream compatibility verification** — a claim about whether this fork's source currently matches
  `aleksandrsvoboda/nurgling2`'s `upstream` remote for the same file(s), verified by direct diff.
  Stamped as `upstream_sha`: the exact `upstream` commit the diff was run against. **A single matching
  `upstream_sha` only proves byte-identity as of that comparison** — it is not proof the fork hasn't
  since diverged, and it is not proof upstream hasn't since changed (upstream churns independently of
  this fork; `ChunkNav` alone had ~20 commits touching `ChunkNavExecutor.java` in the 90 days around
  the navigation audit). Never infer fork-exclusivity or upstream-identity from directory structure
  (`src/nurgling/**` is not automatically fork-exclusive — see `upstream-compatibility.md`); only a
  direct diff against a recorded `upstream_sha` supports that claim.
- Every capability area audited so far (navigation, workstation/production, bot lifecycle) recorded
  its upstream-identity conclusion qualitatively ("byte-identical to upstream") without stamping the
  exact `upstream_sha` compared against. `docs/knowledge/capability-index.md`'s seeded records
  therefore carry `upstream_sha: unknown` for all of them — this is accurate, not a gap to silently
  paper over by backfilling today's `upstream/master` SHA into an old record (that would misrepresent
  when the comparison happened). Stamp a real `upstream_sha` only from a comparison made from this
  point forward.

## Correction-propagation checklist

Demonstrated ad hoc twice already in this audit (the Oven/Bread retraction in this document; the
session-1→session-2 fork-exclusivity correction in `capability-navigation.md`) — both followed the
same shape without it being written down as a procedure. Writing it down so it isn't missed by
accident on a future correction:

1. Mark the old conclusion's status/state as superseded (or the most specific terminal state that
   applies) **in place**, in the doc that made the claim. Do not delete it — the failure mode and the
   correction are both worth keeping, same as the Oven/Bread section above.
2. State the reason for the correction next to the superseded marker, not just the new conclusion —
   future readers need to know *why* the old claim was wrong, not only that it was.
3. `grep` (or equivalent) across `docs/**` for other references to the old claim — a short pointer
   elsewhere (e.g. a `docs/knowledge/capability-index.md` row, a `docs/knowledge/reuse-candidates.md`
   row, a routing-table entry) that still asserts the superseded conclusion is exactly the
   contradiction this methodology exists to prevent. Update every one found, or at minimum leave a
   note on it pointing at the correction.
4. If a `docs/knowledge/*.md` index row pointed at the corrected section, re-run
   `tools/knowledge/validate_knowledge.py` afterward — a corrected `evidence_doc` target that moved or
   a `verified_commit` that should now bump will surface there.

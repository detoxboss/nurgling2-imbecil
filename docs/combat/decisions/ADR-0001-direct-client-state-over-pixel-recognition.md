# ADR-0001: Direct client-state reads instead of screen/pixel recognition

- Status: Accepted
- Date: 2026-07-27
- Decision owners: User (explicit request framing the entire port), implemented by Claude Code
- Related contract IDs: [BEH-SCOPE-001](../behavior-contract.md#lifecycle-and-scope), [BEH-FAIL-001](../behavior-contract.md#fail-closed-handling)
- Related implementation: `src/nurgling/combat/CombatStateAdapter.java`
- Related evidence: [`EV-BRIEF`](../evidence.md), [`EV-SPEC-R3`](../evidence.md), [`EV-IFACE-R3`](../evidence.md)

## Context

The existing automation was an AutoHotkey script reading fixed screen regions (`ImageSearch`/`PixelSearch`) and sending synthetic keystrokes. The R3 audit documents ([`EV-SPEC-R3`](../evidence.md), [`EV-IFACE-R3`](../evidence.md)) extensively catalog the resulting defects: coarse 0-4 "bucket" values instead of real percentages, `search_error`/`valid_not_found` collapsed into false zeros, an unsafe IP-fallback defect (C2), no target-identity detection, and fragility to DPI/theme/window-placement changes.

## Decision

Read `haven.Fightview`/`haven.Fightsess` (and nurgling's `NFightsess`) fields directly — `Relation.ip`, `Buff.ameter()` on resource-name-matched opening buffs, `Fightsess.actions[].cs/ct` — instead of any pixel/template recognition. Dispatch actions through the same `wdgmsg`-based protocol path normal keyboard input uses, not synthetic OS keystrokes.

## Alternatives considered

- **Port the AHK approach 1:1 to Java (still pixel-based).** Rejected: would carry forward every defect the R3 audit catalogs, for no benefit — the Java client already exposes the real values internally.
- **OCR-style recognition of an in-client rendered widget.** Not seriously considered; the underlying typed fields are directly accessible in the same process, making any recognition layer strictly worse.

## Consequences

- Positive: exact numeric openings/IP instead of 0-4 buckets; no `search_error`/`display_absent` conflation risk from pixel matching; immune to DPI/theme/window changes; target-identity is a real gob id, not "whatever the fixed ROI shows now."
- Negative: introduces a real, if small, coupling to internal `haven` field names/types that can change on an upstream merge (see [`CLAUDE.md`](../../../CLAUDE.md)'s hafen-integration guidance) — a risk pixel-reading never had, traded deliberately for correctness.
- The 7-state validity vocabulary from the R3 documents ([`BEH-FAIL-001`](../behavior-contract.md#fail-closed-handling)) was carried forward into `CombatOpeningState`, even though the failure modes it originally described (search errors, template misses) mostly don't apply to direct field reads — kept anyway so "missing/loading" data is still never silently treated as zero. See [UNR-002](../unresolved.md#unr-002) for where this carry-forward is currently incomplete.

## Verification

[`VER-ADAPTER-001`](../verification.md), [`VER-STATE-001`](../verification.md) — both manual code-trace only; no automated harness exists in this repository.

## Supersedes / superseded by

Supersedes the AHK screen-reading approach entirely (not a code supersession — no Java pixel-reading code ever existed to replace).

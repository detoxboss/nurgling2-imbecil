# Qodana findings triage — commit 3a35b8b8a

**Kind:** current. Triage of the first complete Qodana scan (11,738 results / 1,465 files, see
[`qodana-audit.md`](qodana-audit.md)). This is **not** a full triage of all 11,738 findings — that
is a separate, larger undertaking (see [`README.md`](README.md)'s status section). This covers:

1. The 5 findings Qodana flagged `High` severity with a single occurrence each — individually
   read and verified against source.
2. A grouped, **not individually verified**, breakdown of the two correctness-relevant checks with
   the most occurrences (`DataFlowIssue`, `StringEquality`), by message pattern and file area.

No production code was changed to produce this triage.

## 1. The 5 single-occurrence High-severity findings — verified

| # | Rule | File:line | Verdict |
|---|---|---|---|
| 1 | `InfiniteRecursion` | [`src/haven/Debug.java:175`](../../src/haven/Debug.java#L175) | **CONFIRMED bug, zero runtime impact (dead code)** |
| 2 | `LoopStatementsThatDontLoop` | [`src/nurgling/actions/bots/BoughBee.java:47`](../../src/nurgling/actions/bots/BoughBee.java#L47) | **CONFIRMED structural issue, impact unclear — needs a human design call** |
| 3 | `MismatchedArrayReadWrite` | [`src/nurgling/actions/bots/pickling/GlobalBrinePhase.java:157`](../../src/nurgling/actions/bots/pickling/GlobalBrinePhase.java#L157) | **CONFIRMED dead write, no observed functional impact** |
| 4 | `ComparatorMethodParameterNotUsed`* | [`src/haven/render/gl/GLDrawList.java:83`](../../src/haven/render/gl/GLDrawList.java#L83) | **PLAUSIBLE latent contract violation, likely benign in practice** |
| 5 | `JavacQuirks` | [`src/nurgling/cookbook/GenericTypes.java:59`](../../src/nurgling/cookbook/GenericTypes.java#L59) | Not a correctness issue — compile-time perf note only |

\* this is the rule Qodana's summary line labelled "Suspicious 'Comparator.compare()' implementation".

### 1. `Debug.dump1(T thing)` — infinite recursion

```java
public static <T> T dump1(T thing) {
    return(dump1(thing));   // calls itself, not dump1(thing, true) or dump1(thing, fmt)
}
```

Calls itself instead of one of the two sibling overloads (`dump1(T, boolean)` / `dump1(T,
Function)`) that actually do something. Any call to this exact overload would stack-overflow.
**Verified:** repo-wide search for `Debug.dump1(` with a single argument found **zero callers** —
this overload is currently unused. Real bug, but dormant; worth a one-line fix (call
`dump1(thing, true)`) whenever that file is next touched, not urgent on its own.

### 2. `BoughBee.java` — `while(true)` that doesn't loop

Every exit path inside the loop body is a `return` (`Results.ERROR(...)` or `Results.SUCCESS()`) —
confirmed by reading the full loop body (lines 47–136): no `continue`, and no path falls through to
loop again. The `while(true)` wrapper is structurally inert; the method always does exactly one
pass (find bonfire → find beehive → wait → raid → return) regardless of the wrapper.

This is flagged as **needs a human call**, not a clear bug, because two readings are both
plausible and only someone who knows the intended bot behavior can say which:
- **If BoughBee is meant to keep harvesting multiple beehives in one run**, the loop is a bug —
  it should `continue` after a successful raid instead of returning, and the `while(true)` reflects
  lost retry intent.
- **If BoughBee is meant to process exactly one beehive per invocation** (e.g. an outer
  scenario/scheduler calls it repeatedly), the `while(true)` is just dead ceremony and removing it
  changes nothing.

### 3. `GlobalBrinePhase.java` — `interactionResult` written, never read

```java
String[] interactionResult = {""};
boolean[] needSecondClick = {false};
NUtils.addTask(new NTask() {
    public boolean check() {
        ...
        if (currentLevel > originalBrineLevel) { interactionResult[0] = "FILLED"; return true; }
        if (currentLevel == 0.0 && originalBrineLevel > 0.0) {
            interactionResult[0] = "EMPTIED"; needSecondClick[0] = true; return true;
        }
        if (originalBrineLevel == 0.0 && currentLevel >= 1.0) { interactionResult[0] = "FILLED"; return true; }
        ...
    }
});
if (needSecondClick[0]) { ... }
```

**Verified** by reading the rest of the method (through its final `return`): only
`needSecondClick[0]` is ever read back; `interactionResult[0]` ("FILLED"/"EMPTIED") is written in
three places and never read anywhere. The method's actual success/failure decision (further down)
independently rechecks `getBrineLevel(jarInHand) >= 1.0` rather than consulting
`interactionResult`. So the dead write doesn't appear to mask a wrong answer — the real decision is
made correctly via a separate check — but it is dead state, and its presence suggests a
distinction (FILLED vs. EMPTIED-then-refilled) that was meant to be surfaced (e.g. in logging or a
different downstream branch) and currently isn't. Low risk, worth a cheap cleanup or a decision on
whether that distinction should actually be used.

### 4. `GLDrawList.java` — comparator never returns 0

```java
private static final Comparator<DrawSlot> order = new Comparator<DrawSlot>() {
    public int compare(DrawSlot a, DrawSlot b) {
        ...three tie-breaks that may return non-zero...
        return((a.sortid < b.sortid) ? -1 : 1);   // never returns 0
    }
};
```

**Verified:** `sortid` is a `final long` assigned once per `DrawSlot` from a shared
`AtomicLong uniqid` — two *distinct* `DrawSlot` instances can never have equal `sortid`, so the
final tie-break correctly resolves any two different slots without needing a 0 case. The contract
violation only matters if `compare(x, x)` — the *same* object compared to itself — is ever called,
which would wrongly report `x > x`. Whether this tree/draw-list structure ever does that during
rebalancing was not traced in this pass (would require following `DrawSlot`'s insert/remove tree
logic, out of scope here). **Plausible but likely benign** — flagged for whoever next touches
render ordering, not treated as confirmed-impactful.

### 5. `GenericTypes.java` — `Javac quirks`

`Map.ofEntries(...)` called with 50+ entries — a compiler/analysis-slowdown note, not a logic bug.
No further action needed; informational only.

## 2. Grouped breakdown — `DataFlowIssue` (1,559) and `StringEquality` (328)

These were grouped by message text and file location, not individually read — treat this as a
map of where to look next, not a verified bug list.

**`DataFlowIssue` (1,559), top patterns by occurrence count:**

| Count | Pattern |
|---|---|
| 344 | `Dereference of 'NUtils.getGameUI()' may produce 'NullPointerException'` |
| 133 | `Method invocation 'msg' may produce 'NullPointerException'` |
| 108 | `Dereference of 'NUtils.player()' may produce 'NullPointerException'` |
| 80 | `Method invocation 'getInventory' may produce 'NullPointerException'` |
| 52 | `Method invocation 'error' may produce 'NullPointerException'` |
| 41 | `Method invocation 'add' may produce 'NullPointerException'` |
| 37 | `Unboxing of 'NConfig.get(NConfig.Key.ndbenable)' may produce 'NullPointerException'` |
| 31 | `Method invocation 'getWindow' may produce 'NullPointerException'` |
| 25×3 | similar `NConfig.get(NConfig.Key.*)` unboxing, and `findItem`/`getGameUI()` dereferences |
| 23 | `Argument 'Finder.findGob(container.gobid)' might be null` |

Over 600 of the 1,559 (≈40%) are two static accessors — `NUtils.getGameUI()` and
`NUtils.player()` — whose static nullability contract isn't being checked at most call sites across
the bot codebase. This is a systemic pattern, not 600 independent bugs: either these accessors are
genuinely safe to deref unchecked at runtime (game always loaded before bots run, in which case the
contract/annotation is overly strict and the warnings are mostly noise), or there's a real class of
startup/session-teardown NPE risk across much of the bot layer. **Not determined in this pass** —
deciding which it is requires checking `NUtils.getGameUI()`/`player()`'s actual nullability
contract and a handful of real call sites, not a blanket judgment call from the SARIF alone.

**`StringEquality` (328):** 313 of 328 (95%) are in `src/haven`, only 15 in `src/nurgling`. A
spot-check (`ACheckBox.java:69`, `if(msg == "ch")`) matches a pattern common in Hafen's wire-protocol
message dispatch, where message-type strings are often interned/constant and `==` is a deliberate
idiom, not a mistake. **Not verified for all 313** — but the file-area skew away from fork code and
toward upstream-derived protocol code suggests most are this same idiom rather than fork-introduced
bugs. The 15 in `src/nurgling` are the ones worth individually checking first if this gets
prioritized.

## What this triage does not cover

- The remaining ~9,800 findings outside `DataFlowIssue`/`StringEquality`/the 5 singles (e.g.
  `SuspiciousIndentAfterControlStatement` 2,623, `UnnecessaryModifier` 1,977, `UNUSED_IMPORT` 930,
  `FieldMayBeFinal` 706, etc.) — mostly style/cosmetic by rule name, not reviewed here.
- Individual verification of each `DataFlowIssue`/`StringEquality` occurrence.
- Any correctness check on `opt/panama` or test sources (see `qodana-audit.md`'s limitations).

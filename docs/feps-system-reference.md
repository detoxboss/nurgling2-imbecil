# FEP / Food / Attribute-Gain System Reference

Reference for anyone doing FEP-related work in this repo (e.g. the table-eating
optimizer in `src/nurgling/actions/OptimizeTableEating.java`). This documents the
*actual client implementation* of the mechanic, cross-checked against the
[H&H wiki FEP page](https://ringworld.haven-and-hearth.com/wiki/FEP), and calls out
where the wiki's description is a simplification of what the client (and presumably
server) actually does.

## Key insight

**This repo already implements the live FEP formula more precisely than the wiki
text.** `nurgling.iteminfo.NFoodInfo` computes, per food item, the exact expected FEP
yield and exact remaining-FEP-to-fill-bar, live, from real client state. Any new
FEP-related feature should read these values rather than re-derive the math from the
wiki.

## Core objects

| Concept | Where it lives | Notes |
|---|---|---|
| Base attributes | `haven.Glob.CAttr` via `Glob.getcattr(String nm)` | Keyed by short codes: `str, agi, int, con, prc, csm, dex, wil, psy`. `.base` = unmodified value, `.comp` = buffed/computed value. |
| FEP bar cap | `gui.chrwdg.battr.feps.cap` (`haven.BAttrWnd.FoodMeter`) | Equals highest current **base** attribute (wiki-documented rule) — but the effective needed amount is reduced by the live variety bonus, see below. Always read live; never hardcode. |
| FEP pool | `gui.chrwdg.battr.feps.els` (`List<BAttrWnd.FoodMeter.El>`) | One entry per attribute-event type accumulated so far this bar; `el.a` = amount, `el.ev()` = event resource. Sum of `el.a` = current pool total. |
| Satiation / gluttony | `gui.chrwdg.battr.cons` (`haven.BAttrWnd.Constipations`) and `gui.chrwdg.battr.glut` (`GlutMeter`, has `.gmod`) | Per-food-category diminishing-returns multiplier. Already folded into `NFoodInfo.calcExpectedFep()` as the `FoodModifiers` satiation factor (section 5) — don't re-model it separately. |
| Variety tracking | `nurgling.widgets.NCharacterInfo.varity` (`Set<String>`) | Client-side tracked set of food *names* that have counted toward the current bar's variety bonus. Auto-clears when the bar visibly resets (an attribute was gained) — see `NCharacterInfo.tick()`. |
| Per-item FEP tooltip data | `nurgling.iteminfo.NFoodInfo`, resolved via `ItemInfo.find(NFoodInfo.class, gItem.info())` | See below. |
| Table bonus | `NGameUI.getTableMod()` (`src/nurgling/NGameUI.java:403`) | Reads the table window's "Food event bonus" label live. `NFoodInfo.calcExpectedFep()` already calls this per item. |
| Realm bonus | `NGameUI.getRealmMod()` (`src/nurgling/NGameUI.java:421`) | Buff-list-derived realm FEP bonus, also folded into `calcExpectedFep()`. |
| Highest base attribute | `NGameUI.getMaxBase()` (`src/nurgling/NGameUI.java:316`) | Max over `chrwdg.battr.attrs`. |

## Mechanics

### 1. Bar cap and fill

The FEP bar's cap equals your highest **base** (unmodified) attribute. Once the pool
total reaches the (variety-adjusted) needed amount, one attribute is chosen via a
weighted lottery — probability = that attribute's pool share ÷ pool total — and its
base increases by 1 (or 2, for foods whose event is e.g. "Strength +2" — see
`NFoodInfo.fep_map`, which maps event display names like `"Strength +1"` /
`"Strength +2"` to short codes `str` / `str2`). The pool then resets (excess FEP is
lost) and the variety set (`NCharacterInfo.varity`) clears, and the cap recalculates
off the new highest attribute.

**Deterministic targeting**: if the pool contains FEP for only one attribute (or only
a chosen set of attributes), the lottery is 100%-weighted there. This is the lever any
optimizer uses to bias which stat gets picked — not something that needs "0% RNG
guaranteed" framing, just pool purity.

### 2. Variety bonus — the wiki's formula is a simplification

The wiki describes the reduction as a flat linear amount per unique food type:
`0.632 * sqrt(highest_attr)` FEP off the bar, cumulative per unique type eaten this
bar. **The actual client-implemented formula is different in shape** —
`NFoodInfo.calcNeededFep()`:

```java
needed = cap - sqrt(0.3999 * maxBase * glut.gmod / (varity.size() + 1)) - cur_fep
```

i.e. the reduction is `sqrt(0.3999 * maxBase * glut.gmod / (N+1))` where `N` = number
of unique food *names* already eaten this bar (`NCharacterInfo.varity.size()`) — this
has **diminishing marginal benefit per additional unique type** (division inside a
sqrt), not the wiki's flat linear-per-item reduction. It's also modulated by the
current gluttony modifier (`glut.gmod`), which the wiki text doesn't mention at all.

Variety credit is per **food name** (recipe/resource identity), independent of which
attribute(s) the food grants — matches the wiki's own Joe example (cooked perch +
blueberries, both pure INT, still count as 2 separate variety credits).

### 3. Feasting bypasses the Energy gate

Per the wiki: eating at a table ("feasting") grants FEP/Hunger irrespective of current
Energy. Field-eating bots in this repo (`FindAndEatItems`, `Eater`) loop on
`NUtils.getEnergy()` because they eat from inventory/containers, not necessarily at a
table. A table-eating feature does **not** need an energy-depletion loop.

**Open / unverified**: whether the "seated in a chair" bonus is a separate
precondition from the table's own "Food event bonus", or whether `getTableMod()`'s
read value already reflects seating. Not yet empirically confirmed — verify in-client
before treating table-eating as seat-independent.

### 4. Per-item live FEP data — `NFoodInfo`

`NFoodInfo` (constructed from the server's tooltip payload for a food item, one
instance per `GItem`) exposes, once resolved via
`ItemInfo.find(NFoodInfo.class, gItem.info())`:

- `fepSum` (public field) — raw total FEP the item provides, unmodified by
  subscription/table/realm/satiation.
- `expectedFep()` — public accessor (added for the optimizer) wrapping the
  package-private `calcExpectedFep()`: the *actual* expected FEP this item will grant
  right now. It is `fepSum * FoodModifiers.fepMultiplier()` — see "Modifier model" below.
- `neededFepForBar()` — public accessor wrapping `calcNeededFep()`: FEP still needed
  to fill the current bar, already variety-adjusted per the formula above.
- `attrFepBreakdown()` — public accessor: `Map<String,Double>` of short attribute code
  (via `fep_map`) → FEP amount, built from the item's `Event[] evs`.
- `isNewVarietyForBar()` — public accessor wrapping `isVarity`: whether this food name
  is *not yet* in `NCharacterInfo.varity` for the current bar (i.e. eating it earns a
  fresh variety credit).

These values must be **re-read fresh before each simulated/actual bite** — satiation
efficiency and the variety set both shift as you eat, so a value computed at the start
of a session goes stale after the first item.

### 5. Modifier model — `FoodModifiers`

`nurgling.iteminfo.food.FoodModifiers` is the one place the prediction arithmetic lives (pure, unit-tested;
`FoodModifierReader` gathers the live inputs from the **item's own session**, never the ambient one):

```
fepMultiplier = (account + realm) * foodEfficacy(glut.gmod) * tableBonus * satiation * salt
expected FEP  = fepSum * fepMultiplier        # NFoodInfo.calcExpectedFep()
hunger filled = listed hunger * combinedSatiation * salt * tableHungerModifier
```

Each rule below is tagged **proven** (the client source decides it), **supported** (an outside source or another
client agrees, not the server) or **unverified**. The server is private: nothing here is a reading of its code, and
neither Nurgling nor Hurricane is treated as authoritative; each rule keeps whichever behaviour has the strongest
evidence.

- `account` is `1.5` subscribed, `1.2` verified, else `1`; `realm` is **added** to it, not multiplied (the
  pre-existing formula `(a*F*g*t + F*g*t*realm) * satiation`, tested against a copy). *Supported* (the fork's own
  working value). Hurricane additionally has 1.3 for subscribed-only; not adopted, see
  `equipment-tooltip-enhancements.md`.
- `satiation` is the product of the satiation entries of **all** of the food's types
  (`FoodModifiers.combineSatiation`). In `battr.cons`, `a` is already the multiplier (`a == 1.0` entries are
  removed, `a > 1.0` is drawn as buffed). **Proven:** every type counts (the game's satiation list highlights
  them all) and a single type is exactly its entry. **Supported:** that they multiply (Hurricane). Before the
  follow-up the code kept only the last type's entry, an order-dependent result that matched no mechanic.
  Hurricane's `1 - el.a` is the same mechanic: its `El` stores `1 - a`.
- A food type is mapped to its satiation entry by **index** (as the game's own highlight does). **Unverified**
  that the server's list order survives removals; out-of-range indices contribute nothing.
- `salt` is `1` unless the item carries the `Salted` tip **and** `cons` has a `salt` entry **and** that entry
  is not already one of the food's own types (no double application). **Supported** (Hurricane), **unverified**
  in game.
- **Hunger** = listed hunger x the same combined satiation x the same salt factor x the table's "Hunger modifier".
  **Supported**, adopted as the default: Hurricane implements it (`FoodInfo.java:101,107,159`) and says so in its
  character-sheet tooltip (`BAttrWnd.java:506`), the Ring of Brodgar "Food Satiations" page gives
  `HungerGained = FoodHungerValue * SatiationModifier`, and no source contradicts it. **Not measured** in this
  client. The hunger-bar efficacy (`gmod`), the account bonus, the realm bonus and the table's FEP bonus are
  FEP-only: Hurricane does not apply them to hunger either, and there is no evidence for it. Satiation uses this
  fork's native `el.a` (the multiplier), never Hurricane's inverted `1 - el.a`. Salt is applied once: not at all
  if the salt entry is already one of the food's own types. If the table's hunger label cannot be read, hunger
  is unknown (shown as listed, with a note) while the FEP prediction stays available.
- The (+2 weighted) total uses the same multiplier on `FoodInfo.sev`, and is shown only when `sev` is plausible
  (between the FEP sum and twice it). The constructor-position mapping of `sev` is **unverified**.
- `realm`: read from the `RealmBuff` widgets through `RealmBuff.info()`. (The old reader used the shadowed
  `Buff.info` field and always got 0.) The server no longer lists `ui/rbuff`, so this may be dead in the
  current world.
- **Incomplete inputs** never become neutral factors: a still-loading input is *pending*, an unreadable one
  *unsupported*, and either suppresses the prediction (see `equipment-tooltip-enhancements.md`).
- "FEP bar after eating" divides by the variety-adjusted target (`cap - sqrt(0.3999 * maxBase * gmod / (N+1))`,
  the same figure `calcNeededFep` uses), not by the cap. For a food already eaten this bar, `calcNeededFep`
  uses the bare cap — pre-existing, and not changed because the effect of the earlier variety credits on the
  target has not been verified.

#### Checking the hunger prediction in game (no server source needed)

The hunger model is a prediction, not a proven reading of the server. This procedure measures it on one food.

1. Pick a single item (not a stack) whose types currently have a clearly reduced satiation entry, away from a
   feast table (so the table's hunger modifier is 1). Do not eat or drink anything else during the test, and
   take both readings within a few seconds, because hunger also falls slowly with time.
2. **Before:** hover the hunger meter on the character sheet and write down the hunger value in permille (the
   tooltip reads `<label>: N.N‰`). Hover the food (without Shift) and write down: the "Hunger" figure in the
   Energy line (the tooltip uses percent, so predicted ‰ = percent x 10), the `Modifiers` rows (satiation x,
   salt x, table), and the `Hunger x` row. Then hold Shift and write down the listed (unmodified) hunger.
3. Eat the item (right-click, Eat).
4. **After:** read the hunger meter tooltip again. `delta = after - before` in permille.
5. Compare. Predicted hunger is listed x (satiation x salt x table); the model says `delta` equals it, and
   `delta / listed` equals the `Hunger x` figure. Rounding in the tooltips is about 0.1 permille, so allow about
   that much, more for a small food.
   * `delta` close to the predicted figure: the model holds for this food.
   * `delta` close to the **listed** (Shift) figure although `Hunger x` is clearly below 1: satiation does
     not scale hunger. Revert it by making `FoodModifiers.hungerMultiplier()` return only the table
     modifier (and update the docs).
   * Something else: record the numbers (food, both satiation entries, salt yes/no, before/after) before
     changing anything.
6. Repeat once for each of: a food with a single reduced type, a food with two reduced types (tests the
   product), and a salted food (tests the salt factor). A fresh, unsatiated food only checks that nothing is
   scaled wrongly (`Hunger x` = 1).
Tooltip presentation (Shift = unmodified values, weighted totals, FEP bar after eating, Deep Worm warning) is
in [`equipment-tooltip-enhancements.md`](equipment-tooltip-enhancements.md).
## Corrections vs. the wiki / third-party (AI-generated) descriptions

Encountered a third-party AI-generated explanation of this system alongside the wiki
page while designing the table-eating optimizer. Two of its claims are **not**
consistent with either the wiki or this codebase and should not be treated as fact in
future work:

- Claimed formula `FEP Required = 100 + (Highest Base Attribute × 2)` — **wrong**.
  Actual rule (wiki-confirmed, matches `feps.cap`): bar cap = highest base attribute,
  no `100+` offset, no `×2`.
- Claimed "Energy depletion burn loop" (dig/mine to drop Energy back down so you can
  keep eating) as required for continued eating — **not applicable to table
  feasting**, which bypasses the Energy gate per the wiki's own "Feasting... provides
  both FEPs and Hunger, irrespective of current Energy" line. That burn-loop pattern
  only applies to field-eating outside a table.

Everything else in that description (weighted lottery, variety/diminishing-returns
concept, "fill bar with pure target-stat food for deterministic gain", "sort by FEP
density and use small filler items to minimize overfill") was directionally correct
and is reflected in the optimizer's design, but the two points above were pure
hallucination and should be treated with suspicion if seen again in future
AI-assisted research on this system.

## See also

- `src/nurgling/iteminfo/NFoodInfo.java` — per-item live FEP computation.
- `src/nurgling/widgets/NCharacterInfo.java` — variety-set tracking, persisted char info.
- `src/nurgling/widgets/TableInventoryExtension.java` — table widget FEP bar / stats UI,
  and (once implemented) the "Optimize Eating" button + priority-stat picker.
- `src/nurgling/actions/OptimizeTableEating.java` — the table-eating optimizer action.
- `src/nurgling/actions/FindAndEatItems.java`, `src/nurgling/actions/SelectFlowerAction.java`
  — existing field-eating mechanism (`"iact"` → flower menu → `"Eat"`), reused by the
  optimizer for the actual eat trigger.

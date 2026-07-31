# Staying in sync with Aleksander's Nurgling2

Your repo (`detoxboss/nurgling2-imbecil`) is a GitHub **fork** of Aleksander's repo (`aleksandrsvoboda/nurgling2`). A fork just means: same history up to a point, then two lines of commits diverge — his and yours. Syncing means periodically pulling his new commits into your line without disturbing yours.

This is the same problem `docs/hafen-integration-guide.md` solves one layer up (haven → nurgling). The rules below mirror that guide's rules, applied to nurgling2 (Aleksander) → nurgling2-imbecil (you).

## What was actually broken

Your `upstream` remote pointed at a placeholder (`https://github.com`) — not a real repository. Nothing was ever being fetched from it. Fixed:

```
git remote set-url upstream https://github.com/aleksandrsvoboda/nurgling2.git
git fetch upstream
```

## Where you stand right now

```
merge-base: 9d7404f (last commit you and Aleksander share)
                                        │
                    ┌───────────────────┴───────────────────┐
                    │                                       │
         upstream/master (Aleksander)              master (you, origin)
         38 commits ahead of merge-base            1 commit ahead of merge-base
         barterstand, autodropper,                 0d284d7 — Combat Reactor
         hafen-integration-2026-07-polity,          Alpha V1 + fishing bot fixes
         etc.                                       + uncommitted work on top
```

- **38 commits behind** — new work from Aleksander you don't have yet.
- **1 commit ahead** — your Combat Reactor Alpha V1 + fishing bot fixes.
- Your working tree also has **uncommitted** edits sitting on top of that (fishing settings, combat reactor tooling, docs).

## Will merging step on your work?

Checked file-by-file against everything Aleksander changed in those 38 commits:

| Area | Verdict |
|---|---|
| `src/nurgling/combat/*`, `docs/combat/*`, `NCombatReactorTool.java` | **No overlap.** Aleksander's side has none of these files — your Combat Reactor is untouched territory. |
| `src/nurgling/NConfig.java` | Changed on both sides — expect a small conflict. |
| `src/nurgling/NGItem.java` | Changed on both sides — expect a small conflict. |
| `src/nurgling/actions/bots/registry/BotRegistry.java` | Your uncommitted edit + his changes — likely conflict (probably both adding bot entries near each other). |
| `src/lang/messages.properties`, `messages_ru.properties` | Your uncommitted edit + his changes — likely conflict (both adding translation keys). |

Five files, all small, all the kind of conflict that's just "keep both lines" — not a design collision.

## The merge itself — never rebase, never cherry-pick

Same rule the project already uses for `haven` → `nurgling` integrations: **always `git merge`, never rebase or cherry-pick** across a fork boundary. Rebasing rewrites commit hashes, so the *next* sync sees Aleksander's already-merged commits as new again — endless re-conflicts. A merge commit just joins the two lines once and remembers it did.

```
git checkout master
git add -A && git commit -m "wip: combat reactor + fishing fixes"   # commit first, don't merge with a dirty tree
git merge upstream/master
# resolve the 5 files above, then:
git add <resolved files>
git commit
ant jar        # verification gate — no test suite in this repo
git push origin master
```

## The ongoing loop (do this often, not once)

```
fetch upstream ──> merge upstream/master ──> conflicts? ──yes──> resolve file-by-file ──> ant jar ──> push origin master
                                                  │no
                                                  └──────────────> ant jar ──> push origin master

(repeat every few days)
```

Small, frequent merges beat one big one. 38-behind was still easy because your changes and his barely overlap; let that gap grow to hundreds of commits over a month and the same 5 conflict-prone files (`NConfig.java`, `BotRegistry.java`, the lang files) accumulate unrelated changes on both sides, making each conflict harder to read.

## Habits that keep future merges this easy

- **Commit your own work in real commits**, don't let it sit uncommitted for days — makes "did my edit or his edit cause this conflict" obvious.
- **New files are always conflict-free.** Your whole Combat Reactor subsystem proves this — because you built it as new files (`CombatReactorController.java`, `docs/combat/*`) rather than editing an existing shared file, Aleksander's 38 commits couldn't touch it.
- **The five recurring conflict files** (`NConfig.java`, `NGItem.java`, `BotRegistry.java`, `messages*.properties`) are shared registries almost everyone edits. Keep your additions there as small, localized diffs (one new line/entry) rather than reformatting — small diffs merge clean, reformatted blocks don't.
- **Never edit a `haven/**` file** you don't need to — those are upstream-owned; touching one just adds a sixth conflict-prone file to the list above.

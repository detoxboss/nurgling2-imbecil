---
description: Offline Ring of Brodgar recipe/gameplay database and its revision-tracked update pipeline — routing rule to tooling docs and the gameplay knowledge index
paths:
  - "tools/wiki-recipes/**"
  - "src/nurgling/craft/recipes.bin"
  - "src/nurgling/cookbook/generic-types.json"
  - "docs/knowledge/gameplay-index.md"
---

Before changing the recipe/gameplay-fact pipeline, read `tools/wiki-recipes/README.txt` — it is the
full technical reference (NRCPDB01 binary format, fetch/compile/pack scripts, the quality-formula
calculator, revision-tracking guarantees). Don't restate its content elsewhere.

`docs/knowledge/gameplay-index.md` is the compact pointer layer for *facts* this pipeline produces
(e.g. `GAME-001`: the Oven/Bread formula-location lesson). It never duplicates a formula or value —
if you're about to copy a number out of `recipes.bin` into a doc, point at the record/source instead.

The client never downloads wiki pages at runtime; `update_recipes.py --check` is the correct way to
see what changed on the wiki since the last accepted snapshot, by exact revision ID — don't write a
new fetcher or scraper for a one-off check.

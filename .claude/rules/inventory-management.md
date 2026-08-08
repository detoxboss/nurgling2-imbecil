---
description: Inventory/stack/grid code — routing rule to the canonical grid-system reference
paths:
  - "src/nurgling/NInventory.java"
  - "src/nurgling/tasks/GetFreePlace.java"
  - "src/nurgling/tasks/GetNumberFreeCoord.java"
  - "src/nurgling/tasks/GetItems.java"
  - "src/nurgling/tasks/GetItemCount.java"
  - "src/nurgling/tasks/GetTotalAmountItems.java"
  - "src/nurgling/tasks/GetNotFullStack.java"
  - "src/nurgling/tasks/GetNotStack.java"
  - "src/nurgling/tasks/WaitNoItems.java"
  - "src/nurgling/tools/StackSupporter.java"
  - "src/nurgling/tools/InventorySnapshot.java"
  - "src/nurgling/actions/StudyEatOrDrop.java"
  - "src/nurgling/actions/TransferToContainer.java"
  - "src/nurgling/actions/Equip.java"
  - "src/nurgling/NWItem.java"
  - "docs/inventory-grid-system.md"
---

Before writing or reviewing code that places, counts, drops, transfers, equips, eats, or studies
items in an `NInventory`/`WItem` grid, read `docs/inventory-grid-system.md` first — it is the
canonical, code-cited reference for this subsystem. Do not duplicate its mechanics here or re-derive
them from scratch.

Safety rules to hold in mind before opening it:

- A stack is one `WItem` at the top level, not N items — don't write a loop that drops/re-scans one
  unit at a time when a single whole-stack message does the job.
- Eat/Study are inherently per-unit (no whole-stack shortcut exists) — the opposite failure mode from
  the one above. Get the direction right before writing either kind of loop.
- Footprint size comes from the loaded sprite, not a static table, and is swapped to `(height, width)`
  at every grid-placement call site — treat `null`/not-ready sprite state as "wait," never as `0x0`.
- A `"drop"` wdgmsg is fire-and-forget; code that needs to know an action actually completed before
  acting further must wait on real state, not assume the send succeeded.

For all of this in full — with file:line citations, the whole-stack-vs-per-unit message reference,
`InventorySnapshot`'s ownership/cursor model, and known gotchas already found and fixed — go to
`docs/inventory-grid-system.md`.

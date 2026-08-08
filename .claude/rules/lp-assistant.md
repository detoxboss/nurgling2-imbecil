---
description: LP Assistant bot and LP discovery-marker system — routing rule to the canonical references
paths:
  - "src/nurgling/actions/bots/LpAssistantBot.java"
  - "src/nurgling/tools/LpExplorer.java"
  - "src/nurgling/tools/LpActionMatcher.java"
  - "src/nurgling/tools/VSpec.java"
  - "src/nurgling/tools/InventorySnapshot.java"
  - "src/nurgling/tasks/WaitLpProductDiscovered.java"
  - "src/nurgling/tasks/WaitLpFirstProduct.java"
  - "src/nurgling/tasks/WaitLpSettlement.java"
  - "src/nurgling/tasks/WaitFirstProgressCycle.java"
  - "src/nurgling/overlays/NLPassistant.java"
  - "src/nurgling/overlays/map/MinimapDiscoveryRenderer.java"
  - "src/nurgling/conf/NLpAssistantProp.java"
  - "src/nurgling/widgets/bots/LpAssistant.java"
  - "src/nurgling/widgets/NCharacterInfo.java"
  - "src/nurgling/actions/StudyEatOrDrop.java"
  - "docs/lp-assistant-bot.md"
  - "docs/inventory-grid-system.md"
---

Before LP Assistant work — bot logic, LP discovery markers, or the shared inventory-snapshot model it
depends on — read both:

- `docs/lp-assistant-bot.md` — the bot itself: discovery tracking, the `clickedGob` trap, storage
  format, confirmation/interrupt-timing tasks, and the round-by-round history of bugs found and fixed.
- `docs/inventory-grid-system.md` §7 — `InventorySnapshot`'s baseline/ownership/cursor model, which
  `LpAssistantBot` uses to decide what it's actually harvested and safe to touch. Do not re-derive
  this model from the bot code; read it there.

Do not duplicate either document's content here, and do not re-narrate the bot's full implementation
history in this file — it belongs in `docs/lp-assistant-bot.md`.

Safety/accuracy rules:

- Keep code-confirmed claims, build-confirmed claims, and live-runtime-confirmed claims clearly
  distinguished in anything you write or update — `docs/lp-assistant-bot.md` already does this
  (e.g. "confirmed live 2026-08") and any addition should follow the same discipline rather than
  presenting a code-read or a plausible inference as a tested fact.
- Any bot driving harvests autonomously needs the `MapView.clickedGob` stamp workaround described in
  `docs/lp-assistant-bot.md`'s "clickedGob trap" section, or `LpExplorer` discovery tracking will not
  register — this is a systemic gap in the underlying feature, not specific to `LpAssistantBot`.
- `nurgling/tools/StackSupporter.java`'s stack-size table is a hand-maintained client heuristic, not
  server-sourced — see `docs/inventory-grid-system.md` §3, don't treat it as ground truth when
  triaging harvested items.

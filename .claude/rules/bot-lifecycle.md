---
description: Bot/task/scenario lifecycle and state-machine framework (Action/Results/NTask/Scenario/BotRegistry) — routing rule to the capability audit and knowledge-index records
paths:
  - "src/nurgling/actions/Action.java"
  - "src/nurgling/actions/Results.java"
  - "src/nurgling/actions/bots/registry/BotRegistry.java"
  - "src/nurgling/actions/bots/ScenarioRunner.java"
  - "src/nurgling/sessions/BotExecutor.java"
  - "src/nurgling/widgets/BotsInterruptWidget.java"
  - "src/nurgling/scenarios/**/*.java"
  - "src/nurgling/tasks/NTask.java"
  - "src/nurgling/tasks/TaskCriticalExitException.java"
---

Before changing the `Action`/`Results`/`NTask`/`Scenario`/`BotRegistry` framework layer,
`ScenarioManager`/`ScenarioRunner`/`BotExecutor` execution, or the `NCore.addTask`/`criticalExit` wait
mechanism, read `docs/audit/capability-bot-lifecycle.md` — framework contracts, the `while(true)`
retry-loop pattern (46 of 276 bot files), the `Results.ERROR` null-dereference call-pattern evidence,
and the `BotsInterruptWidget.MAX_BOTS` overflow gap.

Check `docs/knowledge/capability-index.md` first (`CAP-BOT-001` `Results.ERROR` nullable dereference,
`CAP-BOT-002` `MAX_BOTS = 9` overflow) before opening the full audit doc — it also points at
`docs/knowledge/reuse-candidates.md` where a relevant reuse candidate exists (none tracked for this
area yet).

Core contracts are confirmed identical to upstream; the bot/task implementation layer is 95%+
shared-and-unmodified — confirmed by direct diff (`docs/audit/upstream-compatibility.md`), not
inferred from the `src/nurgling/**` directory. New bots register in `BotRegistry.java` — see
`CLAUDE.md`'s "Two-layer structure" note — this file is not classpath-scanned.

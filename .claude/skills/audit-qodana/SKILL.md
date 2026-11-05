---
name: audit-qodana
description: Run a Qodana JVM scan on this repo using the committed project-model bootstrap, diff against the committed historical baseline, and report genuinely new findings without hiding or suppressing existing ones. Use for "run qodana", "/audit-qodana", "check for new qodana findings", or "did this change introduce new static-analysis problems".
---

# /audit-qodana

Only validated, reusable piece of the Phase 5 "permanent audit system" so far — see
`docs/audit/README.md` for what's still not built. The other `/audit-*` commands requested
(`audit-full`, `audit-changes`, `audit-capability`, `audit-performance`, `audit-workstations`,
`audit-upstream-impact`) are **not implemented**; building them before a capability-registry
methodology exists to drive them would be speculative. Do not claim they exist.

Background and verified behavior: `docs/audit/qodana-audit.md`. Read it before changing anything
here — it documents why the project model is set up this way and what's still unverified (POSIX CI
bootstrap, `opt/panama`, test-root coverage).

## What this does

1. Verifies `qodana` CLI is on PATH (`qodana --version`); if not, stop and tell the user to install
   it (`https://jb.gg/qodana-cli`) — don't attempt to install tooling unasked.
2. Sets `QODANA_BRANCH`, `QODANA_REVISION`, `QODANA_REMOTE_URL`, `QODANA_REPO_URL` from the current
   repo state (`git branch --show-current`, `git rev-parse HEAD`, `git remote get-url origin`) so
   the report carries real provenance instead of the "Unable to find..." warnings seen in the
   original broken scan.
3. Runs:
   ```
   qodana scan --within-docker false -b docs\audit\qodana\baseline-3a35b8b8a.sarif.json -o <scratch-results-dir>
   ```
   into a scratchpad directory, never `.idea/` or a tracked path. `qodana.yaml`'s `bootstrap:` step
   (committed) sets up the project model automatically — do not hand-edit `.idea/*.iml` as a
   substitute; fix `etc/qodana/project-model/*.xml` instead if the model needs to change, and keep
   it in sync with `build.xml`.
4. Reads the resulting `qodana.sarif.json` and reports, grouped by `baselineState`:
   - `new` results — these are genuinely new since the baseline; report each with file:line,
     ruleId and message.
   - everything else stays `unchanged` and is **not** re-reported in full, but the total count is
     stated so nothing looks silently hidden (e.g. "11,738 unchanged, 3 new").
5. Never deletes or rewrites `docs/audit/qodana/baseline-3a35b8b8a.sarif.json` as part of a routine
   run. Refreshing the baseline to a newer commit is a deliberate, separate action the user asks
   for explicitly — treat it like any other "replace the historical record" request: confirm first,
   and update `qodana-audit.md`'s/`qodana-findings-triage-*.md`'s references to the old filename if
   the commit-suffixed name changes.

## Constraints carried over from the original audit request

- Never modify production Java code as part of running this.
- Never auto-apply Qodana's suggested fixes (`hasFixes: true` in the SARIF) — surface findings
  only.
- Never add `--fail-threshold` to a mode that would silently gate CI without the user asking for
  that as a separate, explicit change.

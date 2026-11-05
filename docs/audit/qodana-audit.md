# Qodana audit: setup, root cause, reproducibility, limitations

**Kind:** current. Covers the Qodana JVM scan setup for this repo: why the original scan produced
zero findings, what was changed, how to reproduce it, and what is still out of scope.

## Root cause of the zero-finding scan

The user's original scan (results in a per-machine Qodana cache dir, not in this repo) produced
`qodana.sarif.json` and `baseline.sarif.json` with 0 results, byte-identical (confirmed by SHA-256).
Both came from the intended repo/commit — the scan ran, but inspected nothing, because:

- **Qodana only auto-imports Maven/Gradle projects.** This is an Ant project
  (`AntSupport is not resolved` in the scan's `idea.log`); nothing told Qodana where the source
  roots or the `client.classpath` jars (from `build.xml`) were.
- Qodana therefore created an empty module: `projectStructure/Modules.json` showed
  `"sourceFolders": []`, `Libraries.json` was empty, and `idea.log` logged
  `No source roots detected for module 'nurgling2-release-sync'`.
- With no source roots, 2,322 tracked `.java` files were never inspected as Java. Only ~9 checks
  ran (`qodana_inspections_summary.csv`), almost all Kotlin/Maven boilerplate; the one Java check
  that ran (`UNUSED_IMPORT`) took 0.00s.
- The scan completed in ~80s, consistent with inspections being skipped rather than run.
- `baseline.sarif.json` was saved (to `Downloads/`) *after* the scan and is the same empty file —
  it hid nothing, because there was nothing to hide. **Do not treat it as a real baseline.**

Verified after the fix: with source roots and libraries in place, the same repo/commit produced
**11,738 results across 1,465 files** in ~60s of inspection time. See
[`qodana-findings-triage-3a35b8b8a.md`](qodana-findings-triage-3a35b8b8a.md).

## The fix: a committed, reproducible project model

`.idea/` is gitignored in this repo (`.gitignore` line 15) and is regenerated per machine/worktree,
so a hand-edited `.idea/*.iml` does not survive a fresh clone, a different worktree, or CI. The fix
instead commits a template outside `.idea/` and has Qodana copy it in before every scan:

- **`etc/qodana/project-model/{nurgling2-release-sync.iml,misc.xml,modules.xml}`** — the actual
  project model: `src` as main source, `test` and `tools/render-parity` as test source,
  `opt`/`build`/`bin`/`diagnostics` excluded, the 14 `client.classpath` jars from `build.xml` plus
  JUnit, language level 8 (matches `build.xml`'s `source="1.8" target="1.8"`), JDK 21. All paths use
  `$MODULE_DIR$`/`$PROJECT_DIR$` macros — no machine-specific paths. **Keep this in sync with
  `build.xml`'s `hafen-client` target by hand** — nothing enforces it.
- **`qodana.yaml`'s `bootstrap:` step** copies those three files into `.idea/` before analysis runs.

Verified: deleting the hand-made `.idea/*.iml` content and running `qodana scan` with *only* the
committed `bootstrap:` step (no manual setup) reproduced the same project model
(`sourceFolders: [src, test, tools/render-parity]`, the same two libraries) and the same 11,738
results. This is the actual reproducibility proof, not an assumption.

**Platform caveat:** the `bootstrap:` command is Windows `copy` syntax, verified on this repo's
Windows dev environment — Qodana invoked it via `cmd.exe` there (confirmed by the Windows-style
`mkdir` error hit during the first attempt, before the command was changed). **A POSIX CI runner
has not been tested** and would likely need `mkdir -p .idea && cp etc/qodana/project-model/*.* .idea/`
instead, since Qodana appears to run `bootstrap` through the native shell, not a bundled one. If a
Qodana CI job is added, verify the bootstrap step there before relying on it.

## Exclusions

- **`src/com`, `src/dolda`** (53 files) — excluded in `qodana.yaml`'s `exclude:` block. Per
  `CLAUDE.md`, these are bundled third-party libraries (jogg/jcraft, dolda jglob/coe/xiphutil), not
  project code. This is a path exclusion, not a suppressed inspection rule.
- **`opt/panama`** (53 files) — excluded via `excludeFolder` in the project model, not `qodana.yaml`.
  It only compiles under JDK ≥ 22 (`build.xml`); the configured `projectJDK: 21` can't parse it.
  **Not investigated further** — out of scope for this pass. To cover it, a second Qodana module (or
  a separate scan) with JDK 22 would be needed; nobody has verified whether that's worth the added
  CI/scan complexity.
- **Test coverage:** `test` and `tools/render-parity` are declared as test sources and were in scope
  for the verified rerun, but produced **0 findings** in the `qodana.recommended` profile. **Not yet
  investigated** whether the recommended profile actually runs meaningful checks against test roots,
  or whether that's a second, separate gap (analogous to the original zero-findings bug but smaller
  in scope). Flagging this rather than assuming either answer.

## Reproducing a scan

```
qodana scan --within-docker false --clear-cache -o <results-dir>
```

Set `QODANA_BRANCH`, `QODANA_REVISION`, `QODANA_REMOTE_URL`, `QODANA_REPO_URL` first (`git branch
--show-current`, `git rev-parse HEAD`, `git remote get-url origin`) — without them the report has no
branch/commit/remote recorded (`GitReportContributor` warnings in the log), as happened in the
original scan.

## Baseline usage (does not hide findings)

`docs/audit/qodana/baseline-3a35b8b8a.sarif.json` is the full SARIF from commit `3a35b8b8a`
(11,738 results, 1,465 files) — the real baseline, replacing the empty one. To find only *newly
introduced* problems on top of it without suppressing anything from modernization analysis:

```
qodana scan --within-docker false --clear-cache -b docs\audit\qodana\baseline-3a35b8b8a.sarif.json -o <results-dir>
```

Verified: with `-b` pointing at this baseline and no source changes, the resulting
`qodana.sarif.json` still contains all 11,738 results (nothing removed) but each gets
`"baselineState": "unchanged"`; the CLI's quality-gate count ("Found N new problems") drops to 0
because nothing is actually new. Only genuinely new/changed findings would get `"baselineState":
"new"` and be counted. The baseline **marks**, it does not **delete** — a report reader that walks
the full SARIF (as the triage doc does) still sees every pre-existing finding.

## Known limitations of this pass

- `opt/panama` and the recommended profile's apparent test-root blindness are flagged, not resolved.
- The `bootstrap:` command is Windows-only-verified; CI portability is unconfirmed.
- `etc/qodana/project-model/*.iml` must be updated by hand if `build.xml`'s classpath/source roots
  change — there is no automated sync.

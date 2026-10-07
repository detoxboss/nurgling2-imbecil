---
description: Portable release packaging — routing rule to the canonical reference
paths:
  - "build.xml"
  - ".github/workflows/*.yml"
  - "etc/release-*"
  - "etc/run*.bat"
  - "etc/run*.sh"
  - "docs/release-process.md"
---

Before changing or reviewing release packaging, read `docs/release-process.md` first.

- This fork publishes **draft GitHub Releases directly in this repo**, via manual `workflow_dispatch`
  only. It does not push to, or depend on, `aleksandrsvoboda/nurgling-release` — do not reintroduce
  that architecture or a `RELEASE_REPO_TOKEN`-style secret.
- `ant bin` is the packaging basis, unmodified — do not duplicate its dependency-download or
  native-library logic in the workflow, and do not change `build.xml` for this area without a concrete,
  unavoidable blocker.
- Never add `steam_appid.txt` to the repo or to a release asset — dev/testing-only per Valve, see
  `docs/release-process.md`'s Steam section.
- Don't claim a platform launch or Steam login works until a person has actually run the manual
  verification steps in `docs/release-process.md` — static inspection is not sufficient sign-off.
- The legacy updater system (`etc/run_updater*.bat`, `nurgling_launcher.jar`, `NConfig.Key.baseurl`)
  is intentionally left in place but excluded from new release archives — it is not dead code to
  "clean up" as part of a packaging change; removing it is a separate, not-yet-made decision.
  Correction (2026-10-07 sync — **the 2026-09-30 note here was wrong and has been replaced**): that
  note claimed `build.xml`'s `pre-release`/`version` targets no longer existed in this fork. Verified
  against current source: **`pre-release` does still exist** (`build.xml`, target `pre-release`,
  depends on `build-env, jar, extlib/jogl, extlib/jogl-macos, extlib/lwjgl-vk, res-jar`), and so does
  `release` (which is just `depends="pre-release"`). Only the `version` target is gone, along with the
  updater files `pre-release` used to stage (`etc/run_updater*.bat`, `nurgling_launcher.jar`). This
  fork's own release workflow calls `ant test`/`ant bin` directly and never `pre-release`, so those
  targets are unused here — but they are present, and `pre-release` is the one place in the tree that
  already writes a Linux `haven-config.properties` with `haven.toolkit=lwjgl`, which matters for the
  open Linux/JOGL item in `docs/fork-maintenance-backlog.md`. As that stale note itself advised:
  verify current `build.xml` directly rather than trusting any summary of it, including this one.

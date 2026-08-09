# Release process

**Kind:** current. This is the sole authoritative reference for cutting a portable release of this
fork. Routed via [`.claude/rules/release-engineering.md`](../.claude/rules/release-engineering.md).

## What this fork does (and does not) publish

This fork (`detoxboss/nurgling2-imbecil`) publishes its own **draft GitHub Releases**, directly in this
repository. It does **not** push to, or depend on, Aleksander's separate
`aleksandrsvoboda/nurgling-release` repo — that architecture belonged to the upstream project this fork
tracks and has been retired here (see "Legacy updater system" below).

## Cutting a release

1. Decide the version number (e.g. `1.0.0`, or a pre-release like `1.0.0-beta.1`). The workflow always
   builds `master` HEAD — there is no ref/revision input.
2. Run the **Create Release** workflow (`.github/workflows/create-release.yml`) via
   `workflow_dispatch` (Actions tab → "Create Release" → "Run workflow"), filling in `version` — the
   version string, without a leading `v`.
3. The workflow validates the version string before doing anything else (rejects a leading `v`,
   whitespace, slashes, shell characters, or an empty value; accepts a conservative semver-like form
   such as `1.0.0` or `1.0.0-beta.1`), checks out `refs/heads/master`, builds on Windows, packages and
   publishes on Ubuntu, and produces a **draft** GitHub Release tagged `v<version>`, targeted at the
   exact resolved commit SHA. It never runs on push — only on manual dispatch — so it cannot create a
   permanent release automatically.
4. Inspect the draft release's assets and notes, then publish it manually from the GitHub UI when
   satisfied. The workflow deliberately stops at "draft" — publishing is a separate, human action.

## What the workflow does

Two dependent jobs:

1. **`build-windows`** (`windows-2022`): validate the `version` input → checkout `refs/heads/master`
   (`persist-credentials: false`) → resolve the exact commit SHA → `ant test` → `ant bin` → verify the
   required `bin/` files are present → verify `bin/nurgling-res.jar` contains a non-empty
   `res/nurgling/hud/loginscr2.res` (see "Windows-only resource build" below) → upload the verified
   `bin/` directory as a workflow artifact.
2. **`package-and-release`** (`ubuntu-latest`, needs `build-windows`): checkout the exact commit SHA
   resolved by the Windows job → download the verified `bin/` artifact → assemble a portable folder
   from `bin/`'s output plus two launchers and a README → verify required files are present and
   forbidden files are absent → build `.zip` and `.tar.gz` from identical contents → `SHA256SUMS.txt`
   → `gh release create --draft`.

The validated version and resolved commit SHA are passed from `build-windows` to `package-and-release`
as job outputs — the raw `${{ inputs.version }}` expression is never interpolated directly into shell
script text after validation, to avoid script-injection risk from workflow-input text. Both jobs
therefore operate on the exact same commit: the Ubuntu job checks out `build-windows`'s resolved SHA
rather than re-resolving `refs/heads/master` itself, so a push to `master` between the two jobs can't
skew them apart.

The packaging job runs on `ubuntu-latest` (not Windows) specifically so `chmod`/`tar` reliably preserve
the executable bit on `run.sh` inside the tarball. It uses the workflow's automatic `GITHUB_TOKEN`,
scoped to `permissions: contents: write` — no personal access token, no repository secret. Neither job
caches `lib/ext` — this is a manually-run, infrequent workflow, so each run starts from a fresh hosted
runner and lets Ant's existing dependency targets fetch their expected current inputs directly.

### Windows-only resource build

`ant bin`'s `resources` target shells out to `etc/LayerUtil.jar` to convert `resources/src` into the
`.res` files packed into `nurgling-res.jar` (`build.xml`'s `resources` target). That conversion is
**not Linux-safe** with the current legacy `LayerUtil` build: on Ubuntu it silently produced a broken,
near-empty `res/nurgling/hud/loginscr2.res` (an 18-byte resource header with no image data) while
logging `Invalid number of decoded files for image` / `Error loading file` — and because the target
declares `failifexecutionfails="false"`, Ant's own exit code never reflected the failure, so a broken
build could still complete "successfully" and produce a client that crashes at `LoginScreen.java:40`
on the very first launch. That is why resource compilation (`ant test` / `ant bin`) runs on
`windows-2022`, not Ubuntu, and why `build-windows` explicitly greps the `ant bin` log for those two
messages and independently checks that `res/nurgling/hud/loginscr2.res` exists inside
`bin/nurgling-res.jar` with an uncompressed size greater than 18 bytes before uploading the `bin/`
artifact. Any future all-Linux fix to `LayerUtil` should re-verify this resource before reverting the
build job back to a single Ubuntu job.

`ant bin` is the packaging basis, unmodified. It already assembles a genuinely cross-platform payload
in one build: JOGL, LWJGL, and Steamworks each ship native libraries for Windows/Linux/macOS as part of
their normal jar layout (LWJGL as one fat jar per component; JOGL and Steamworks as separate
per-platform native jars that their own loaders auto-select at runtime), and `ant bin`'s filesets pull
in the complete set for each. No per-OS build or archive split is needed.

## Release archive contents

```
nurgling-bufu-<version>/
  hafen.jar, hafen-res.jar, builtin-res.jar, nurgling-res.jar
  jogl-all.jar, gluegen-rt.jar, jogl-all-natives-*.jar        (win/linux/macos)
  lwjgl-fat.jar, lwjgl-awt.jar, lwjgl-opengl-fat.jar
  steamworks4j.jar, steamworks4j-natives-*.jar                (win/linux/macos)
  json-java.jar, postgresql-*.jar, sqlite-jdbc-*.jar
  haven-config.properties
  run.bat                  (from etc/release-run.bat; cds into its own directory first, forwards args via %*)
  run.sh                   (from etc/release-run.sh; cds into its own directory first, forwards args via "$@", +x in the .tar.gz)
  README.md                (from etc/release-readme.md, with @VERSION@/@COMMIT@ filled in; describes the build source as "master @ <commit SHA>")
  COPYING                  (byte-for-byte copy of repo root COPYING)
  LICENSE-GPL-3             (byte-for-byte copy of doc/GPL-3)
  LICENSE-LGPL-3            (byte-for-byte copy of doc/LGPL-3)
```

Deliberately excluded: `steam_appid.txt`, the updater batch files (`run_updater*.bat`), and
`nurgling_launcher.jar`. No JRE/JDK is bundled — see "Java runtime" below.

The three license files are copied unmodified from the repository — no substitution or reformatting.
The bundled README points at `https://github.com/detoxboss/nurgling2-imbecil/tree/<commit>` for the
exact source tree this build came from, and notes that GitHub also provides a downloadable source
archive on the corresponding release tag's page — that combination satisfies GPL/LGPL corresponding-
source requirements without bundling a source snapshot in the binary archive itself.

Two archives, `nurgling-bufu-<version>.zip` and `.tar.gz`, contain identical files. The `.tar.gz`
is recommended and verified for Linux/macOS, because its handling of `run.sh`'s executable bit is
predictable; the `.zip` is recommended for Windows. ZIP permission restoration on other platforms
depends on the extractor used — it is not that ZIP inherently cannot preserve the bit, just that
restoring it isn't guaranteed the way tar's is. `SHA256SUMS.txt` covers both archives.

## Java runtime

Not bundled. Runtime requirement: **Java 18–21**; Java 21 is recommended and is what the release
workflow builds and tests with (Temurin 21). Build and test success (`ant test`, `ant bin` completing
without error) is not proof of graphical runtime compatibility on any given platform — actual client
launches remain manually verified, see "Manual verification" below. The shipped launchers use
`--add-exports=java.desktop/sun.awt=ALL-UNNAMED`, a flag syntax that does not exist before Java 9, so
Java 8 is not actually supported despite the project's `source=1.8`/`target=1.8` compile target — that
setting controls bytecode level only, not launcher compatibility.

## Steam mode

One client build supports both native and Steam login — there is no separate Steam package or archive.
Setting `haven.authmech=steam` in `haven-config.properties` switches on the Steam login code path
(`haven.LoginScreen`/`haven.SteamCreds`/`haven.Steam`, selected in `haven.Bootstrap.run()`).

That flag alone is **not** sufficient for a working Steam login. `haven.Steam.init()` calls
`SteamAPI.init()` with no explicit App ID anywhere in source — App ID context comes entirely from
outside the client, either (a) the process having been launched by a real running Steam client, or (b)
for private local testing only, a `steam_appid.txt` file placed next to `hafen.jar` containing H&H's
App ID (`3051280`). **Never commit `steam_appid.txt` or include it in a release asset** — Valve
documents it as a developer/testing convenience, not something to ship.

The release archive already includes `steamworks4j.jar` plus Windows/Linux/macOS Steamworks native
jars (part of `ant bin`'s normal output), so no extra packaging step is needed to support Steam mode.

**Verification status**: a live Steam login from a package built by this workflow has not yet been
performed. Do not describe Steam support as confirmed/working until that manual test has actually been
run — see "Manual verification" below.

## Manual verification

The workflow's own checks are limited to file presence/absence and successful build/test — they do not
prove the client actually launches or logs in. The following must be verified by a person, per release
or per meaningful packaging change, and are **not** automated by this workflow:

- Windows: extract the `.zip`, run `run.bat`, reach the login screen.
- Linux: extract the `.tar.gz`, confirm `run.sh` is executable without a manual `chmod`, run it, reach
  the login screen.
- macOS: same as Linux, ideally on both Intel and Apple Silicon.
- Native login: complete an actual login with real credentials through to character selection, on at
  least one platform.
- Steam login: with a real Steam client running and a manually-placed local `steam_appid.txt`, confirm
  the Steam login button appears and authentication completes.

Until each of these has actually been run against a given release, treat that platform/path as
unverified — do not claim it works from static inspection alone.

## Legacy updater system

`etc/run_updater*.bat`, `etc/nurgling_launcher.jar`, `build.xml`'s `pre-release`/`release`/`version`
targets, and `NConfig.Key.baseurl` (default:
`https://raw.githubusercontent.com/aleksandrsvoboda/nurgling-release/stable/ver`) are unmodified
upstream-derived leftovers from Aleksander's separate release-repo/updater architecture. They are
**not removed** in this pass — only excluded from the new release archives — because other things may
still reference them and removing them is a separate decision.

The one runtime consumer, `NLoginScreen`'s version-notice check, only activates if a local `ver` file
exists next to the client; `ant bin` (and therefore this release process) never produces one, so it is
inert for every package built by this workflow.

`.github/workflows/release-latest.yml` and `promote-to-stable.yml` — the workflows that pushed into
`aleksandrsvoboda/nurgling-release` using a `RELEASE_REPO_TOKEN` secret — have been removed from this
repo. This fork has no legitimate publish access to that separate repo and does not use that
architecture; `create-release.yml` above is the replacement. `.github/workflows/build-pr.yml` is
unaffected and unchanged.

## Version/tag scheme

The release version is a validated `workflow_dispatch` input (conservative semver-like form, e.g.
`1.0.0` or `1.0.0-beta.1` — no leading `v`), not derived from `build.xml`'s `version.num`/`build.num`
(those remain tied to the legacy updater's own versioning and are left as-is, just not used here). Tags
follow `v<version>` (e.g. `v1.0.0`). The workflow always builds `master` HEAD; the exact resolved
commit SHA is recorded in both the release notes and the bundled README ("master @ `<commit SHA>`"), so
every archive is traceable back to an exact commit.

# Nurgling II — portable client

Version: @VERSION@
Built from: master @ commit @COMMIT@

## Launching

Windows: double-click `run.bat` (or run it from a terminal; forwards any arguments with `%*`).
Linux / macOS: `./run.sh` (forwards any arguments with `"$@"`; extract the `.tar.gz`, not the `.zip`,
to keep the executable bit set automatically — see "Archive formats" below).

Both launchers `cd` into their own directory first, so the package can be extracted anywhere and run
without any extra setup.

## Java

Runtime requirement: Java 18–21. Java 21 is recommended, and is what this release was built with
(Temurin 21). A successful build/test does not by itself prove graphical runtime compatibility — actual
platform launches are verified manually; see `docs/release-process.md` in the source repository for
current verification status.

## Steam mode

This is the same client build used for both native and Steam login — there is no separate Steam
package. To enable the Steam login path, add the following line to `haven-config.properties` in this
directory:

```
haven.authmech=steam
```

Enabling this only switches on the Steam login UI/code path. It does **not** by itself give the client
a Steam App ID context — that still requires either a real Steam client launching the process, or (for
private local testing only) a `steam_appid.txt` file placed next to `hafen.jar` containing the H&H App
ID. Never commit or redistribute a `steam_appid.txt` file — Valve documents it as a developer/testing
convenience only, not something to ship.

## Archive formats

`nurgling-bufu-VERSION.zip` and `nurgling-bufu-VERSION.tar.gz` contain identical files. The
`.tar.gz` is recommended and verified for Linux/macOS, because its handling of `run.sh`'s executable bit
is predictable. The `.zip` is recommended for Windows; ZIP permission restoration on other platforms
depends on the extractor used, so don't rely on it there.

## Licensing and source

`COPYING`, `LICENSE-GPL-3`, and `LICENSE-LGPL-3` in this directory are the project's license terms.
Corresponding source for this exact build is:

`https://github.com/detoxboss/nurgling2-imbecil/tree/@COMMIT@`

GitHub also provides a source archive for the exact release tag this package was built from, from that
same release's page.

## Verification

`SHA256SUMS.txt` in the release lists the checksum of each archive.

Windows, Linux, and macOS launches, and Steam login, are verified manually by a person against each
release — this file does not itself constitute proof that every platform/path has been tested for this
specific build. See `docs/release-process.md` in the source repository for the current verification
status of each.

# Village Watch

**Status: current — describes the implementation on branch `feat/village-watch-discord-alerts`.
Automated tests pass; nothing here has been run in a live client yet (see "Verification status").**

A passive lookout bot. One character stands still, watches the players its own session has loaded,
and posts a Discord alert — with a screenshot where rendering allows — when it sees an unknown, white
or red player. It never moves, clicks, attacks, opens gates, logs out or hearths.

## Using it

### Configure a lookout

Open **Village Watch Settings** (Bots menu, UTILS group; also found by Action Search) or, inside a
Scenario, select a Village Watch step and press **Edit Watcher Settings**. The window has four tabs:

| Tab | Contents |
|---|---|
| General | Watcher name (`{watcher}`), scan interval, absence grace, re-alert cooldown, attach-screenshot, **Test Discord**, **Test screenshot**, live status |
| Unknown / White / Red | Per-category enable box, multi-line message template, live preview (sample data), **Restore default** |

Numeric fields are range-checked as you type (the allowed range is shown beside each, red when
invalid) and **Save** refuses invalid values. Hover the three timing controls for their meaning:

* **Scan interval** (1–60 s, default 2) — how often the lookout checks for threats.
* **Absence grace** (5–3600 s, default 30) — how long a detected player must stay unseen before that sighting ends.
* **Re-alert cooldown** (10–86400 s, default 300) — wait before re-notifying about a *continuously present* player. A newly confirmed threat, a reappearance after the grace, or an escalation is reported immediately.

Placeholders: `{watcher}` `{player}` `{status}` `{time}` `{gob_id}` `{coords}`. A value that is
genuinely unavailable renders as `unavailable` — names and positions are never guessed. A *Position*
field is added to the embed when a position is known and the template does not already use `{coords}`.

### Profiles — one per lookout

Settings live in named **profiles** (`Southeast Watch`, `North Watch`, …) in one file,
`village_watch.nurgling.json`, beside the other nurgling data files. **New** / **Copy** / **Delete**
manage them; `Default` always exists. Each character remembers the profile it last saved/started, so
the Bots-menu entry resumes the right one for that character.

* A running lookout works on a **copy taken at start**. Editing or deleting a profile never changes a lookout that is already watching; restart it to apply changes.
* Every client window reads the same file on each use, so profiles made in one window are visible in the others. Writes are atomic.
* The webhook itself is the existing **Settings → Discord → "general"** webhook; Village Watch adds none. Only `https://discord.com|discordapp.com/api/webhooks/…` URLs are accepted and the URL is never logged or put in an error message.

### Start it

* **Bots menu:** *Village Watch* runs the character's last-used profile. Or press **Save & Start** in the settings window (**Stop watching** replaces it while running).
* **Scenario:** add the *Village Watch* step (✪ marks it as configurable), pick its profile in the step panel. The step stores only the profile **name**. `ScenarioRunner` runs steps sequentially and Village Watch never finishes on its own, so **make it the last step** of its Scenario. If the profile no longer exists, or no webhook is configured, the step fails with a message instead of watching with the wrong settings.
* **Stop:** the normal bot-stop control, the settings window's **Stop watching**, or closing the session.

## Behaviour

### Classification (`WatchClassifier`, `GobScanner`)

| Situation | Verdict |
|---|---|
| Own character, party member, `dead`/`manneq`/`skel` pose | ignored |
| Model (Composite) not loaded, or `Buddy` attribute present but its buddy record / the buddy list not loaded | **pending** — never alerts |
| No `Buddy` attribute (character not memorized), map fully loaded | **Unknown** |
| `Buddy` attribute, kin group 0 | **White** |
| `Buddy` attribute, kin group 2 | **Red** |
| `Buddy` attribute, any other group | ignored |

Unknown ≠ group 0: a gob with no `Buddy` attribute is unmemorized; group 0 is a memorized white
player. (`NAlarmWdg` folds the two together; Village Watch does not and does not depend on the local
alarm's per-group settings, auto-hearth or auto-logout.) The buddy record is looked up from the
session's `BuddyWnd` directly, because `Buddy.b` is only populated when a nameplate is drawn and a
background session never draws. Reused safeguard: `NAlarmWdg.isMapFullyLoaded()` (the same
"all 9 grids" check the local alarm waits for before trusting a missing buddy). An unknown player
must additionally stay unknown for 3 s (`ThreatTracker.UNKNOWN_SETTLE_MS`) before it is reported.

### Duplicate suppression (`ThreatTracker`)

Per running lookout, keyed by gob id (ids are only meaningful inside their own session, so trackers
are never shared). New → alert at once; present → quiet; present past cooldown → repeat; category
rises (white/unknown → red, white → unknown) → alert at once even inside the cooldown; falls → quiet
(a later rise alerts again); unseen longer than the grace → forgotten, next sight is new. At most 256
sightings are remembered. Bot restart = fresh tracker. Different lookouts may report the same player;
that is intended (location context).

## Discord (`nurgling.notifications`)

* `DiscordMessage` builds one embed (title, template as description, category colour, footer, timestamp, optional fields). JSON comes from `DiscordHookObject.toPayload()` (extracted from the old sender, which is otherwise unchanged and still used by the existing features). Messages set `allowed_mentions: {parse: []}` so a player name or template can't ping anyone.
* With a screenshot the request is `multipart/form-data` with `payload_json` + `files[0]` and the embed shows it via `attachment://<file>.jpg`; without one it is plain JSON, as before.
* `DiscordWebhookQueue`: non-blocking; 16-message bounded queue and one daemon sender thread; 10 s connect / 20 s read timeouts; up to 4 attempts; 429 honours `Retry-After` (capped 30 s); 5xx and network errors back off 1/2/4 s; other 4xx fail at once with Discord's own message; a 413 on an attachment resends text-only. Queued screenshot bytes are capped (16 MB total, 7 MB each); over budget the message still goes, text-only. **Success is reported only after Discord answers 2xx.**
* Failures show in chat as `Village Watch: alert not delivered (<reason>)`, and the status line shows the last delivery result.

## Screenshots

`MapScreenshotProvider` reuses the render hook the Screenshot tool uses — `MapView.delay2(...)` +
`GOut.getimage(...)` — with no upload window, no focus change and no camera movement. The frame is
shrunk to ≤1280 px wide and JPEG-encoded (≤1.5 MB) in memory. One capture serves every alert raised
in the same scan. If the threat is outside the camera view the alert says so (the camera is never
moved).

| Setup | Screenshots |
|---|---|
| **A. Separate client windows**, one lookout each | Supported. Each window captures its own map view. |
| **B. Several sessions in one window** — the on-screen session | Supported (same as A). |
| **B. Several sessions in one window** — any background session | **Not supported.** Text alert only, with *Screenshot: Not attached: this session is in the background…* in the embed. |

Why background sessions can't (code read, not live-tested): `SessionContext.demoteToHeadless()` stops
rendering the session, and `promoteToVisual(env)` hands it the window's single GL environment; the
capture hook runs inside that session's own `MapView.draw`, which only the on-screen session executes.
A capture request on a background session would never be answered, so it is refused up front rather
than waited on (a request is also abandoned after 5 s so a late frame is never attached to a later
alert). Supporting it would mean rendering a second scene on the window's GL context per alert —
Hafen graphics work this feature deliberately does not do. **Recommendation:** use separate client
windows (Configuration A) for any lookout that needs pictures. A frame is also not produced while the
window is minimized or the scene is still loading; the alert goes out text-only and says why.

## Files

New, all feature-owned: `src/nurgling/watch/**` (logic, store, scanner, screenshot, status),
`src/nurgling/watch/ui/**` (settings window, Scenario step panel),
`src/nurgling/actions/bots/VillageWatchBot.java`, `…/VillageWatchSettingsAction.java`,
`src/nurgling/notifications/Discord{Message,Transport,WebhookQueue}.java`,
`resources/src/nurgling/bots/icons/villagewatch{,cfg}/**`, tests under `test/nurgling/watch/`,
`test/nurgling/notifications/`, `test/nurgling/actions/bots/VillageWatchBotSettingsTest.java`.

Shared files touched (each a small hook — see `fork-customization-ledger.md` → "Village Watch"):
`BotRegistry.java` (+2 registrations), `StepSettingsPanel.java` (+4 lines), `StepListWidget.java`
(+1 condition), `NAlarmWdg.java` (public getter, field made `volatile`), `DiscordHookObject.java`
(`toPayload()` extracted from `run()`; behaviour of `run()` unchanged). No `src/haven/**` file is modified.

## Verification status

* `ant jar` and `ant test` — see the PR description for the exact counts. Automated coverage: classification (incl. delayed buddy loading), tracker (cooldown, grace, escalation, restart, isolation, bounds), templates, profile persistence/copy-isolation/clamping, Discord payload + multipart bytes, delivery/retry/rate-limit/413/queue bounds, URL validation and non-disclosure, screenshot encoding, engine behaviour with a failing/unavailable/interrupted screenshot provider, Scenario step → bot settings (incl. save/reload).
* **Not covered by automated tests, and not run live:** the real gob scan against a logged-in character, the settings window layout, the real HTTPS call to Discord, real frame capture, session close/disconnect handling, and thread shutdown of the running bot (needs a live session). These are listed below as manual checks. Do not read this document as saying they passed.

## Manual test plan (all `NOT RUN`)

1. One rendered client, one lookout: start from the Bots menu; status shows *Watching*.
2. Unknown character walks in → alert after ~3 s with the Unknown template.
3. Memorized white-group character → White alert immediately, with its memorized name.
4. Memorized red-group character → Red alert.
5. Alert shows the screenshot inside the embed (not just as a loose attachment); with the player out of view, the "outside the camera view" note appears.
6. Stay in range → no repeat until the cooldown; leave for longer than the grace and return → immediate alert; change white→red while present → immediate escalation alert.
7. Two separate client windows, two characters, two profiles: each posts its own watcher name and its own screenshot.
8. Two sessions in one window: the on-screen one posts a screenshot, the background one posts text-only with the reason.
9. Switch the active session while watching: no wrong-view screenshot ever; the formerly-active session turns text-only.
10. Stop (settings window and normal stop) and restart: no duplicate loops, status returns to *Not running*; closing the session ends the bot.
11. Save two profiles, restart the client, reload: values and templates persist; editing one while the other runs doesn't change the running lookout.
12. Scenario with Village Watch as last step: profile choice persists across saving the Scenario; delete the profile → the step fails with a message.
13. Bots menu *Village Watch* / *Village Watch Settings* and Action Search both find the entries.
14. Existing Discord features (Settings → Discord test button, Forager sightings) still post.

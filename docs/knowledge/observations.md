# User gameplay observations

**Kind:** current (this registry, as a container — individual records are explicitly *not*
`current`-verified mechanics). A home for gameplay context supplied by the user that isn't yet, and
may never be, a verified mechanic. See `docs/knowledge/README.md` for the record format.

**An observation is not a fact.** Adding a record here never upgrades it to a verified game mechanic
— that only happens if someone independently checks it (against the live wiki, server behavior, or
source) and records that check, at which point the finding belongs in `gameplay-index.md` or the
relevant audit doc, citing this record as the originating report. Don't duplicate a fact already in
`recipes.bin` or an existing authoritative doc — if the observation just confirms what's already
documented there, note the confirmation on the existing record instead of opening a new one here.

## Required fields

- **id** — `OBS-NNN`.
- **topic** — the capability or gameplay subject this relates to.
- **status** — verification status: `UNVERIFIED` (reported, not checked), `USER_REPORTED` (reported
  with enough detail to act on but still unchecked), `CORROBORATED` (checked against another source
  and consistent), `CONTRADICTED` (checked and found wrong — keep the record, state what's actually
  true and where), `SUPERSEDED` (promoted into a verified doc; this record stays as the origin trail).
- **date** — when observed/reported (convert relative dates to absolute).
- **evidence** — what's the source: a description of what was seen, a screenshot reference, a
  character/world name, a log excerpt, etc.
- **world/version** — optional but record when known; mechanics can change between server resets or
  content patches.
- **related** — optional links to affected `capability-index.md`/`gameplay-index.md` records.

## Example record (template only — not an actual observation)

<!-- knowledge-record
id: OBS-000
type: observation
topic: TEMPLATE — replace with the real topic before use
status: UNVERIFIED
date: 2026-11-05
evidence: TEMPLATE — describe what was actually observed and where
-->
**OBS-000 — Template. Delete this line's bold title and write a real one when adding the first real observation.**
This record is a format example, not a reported finding; it exists to show required metadata shape
and must not be read as representing something true about the game. Remove the `TEMPLATE` markers
when the first real observation is added, and renumber starting at `OBS-001`.

No real observations have been recorded yet.

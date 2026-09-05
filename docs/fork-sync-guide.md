# Syncing this fork against upstream Nurgling2

This repo (`detoxboss/nurgling2-imbecil`) is a GitHub fork of `aleksandrsvoboda/nurgling2`. Syncing
means periodically pulling that project's new commits into this fork's line of history without
disturbing this fork's own intentional divergence. This is the fork-boundary equivalent of what
`docs/hafen-integration-guide.md` does one layer down (official Hafen → `src/haven/**`); the two
procedures share their core discipline (merge-only, never rebase/cherry-pick across the boundary,
safety tag first, manual review of shared-file auto-merges) but are separate procedures for separate
remotes — don't conflate them.

This document describes the **procedure**. It intentionally carries no current SHAs, commit counts,
or "where things stand right now" — that kind of fact belongs in `docs/upstream-sync-history/` (what
happened, per cycle) and `docs/fork-customization-ledger.md` (what must survive, and why). A sync
starts by deriving all of that fresh from the repository, not from this guide.

## Starting a sync

A sync can start from a simple request — for example:

> "Upstream has new commits again. Analyze the new divergence and start the fork-sync procedure."

Nothing about that request carries any ref, count, or date. The procedure below derives all of it
dynamically, every time, from `git fetch` and the repository's own history. Treat any SHA or count
mentioned in a request itself as a claim to verify, not a fact to trust.

## The four phases

### Phase 1 — Analyze

1. **Fetch both remotes** (`git fetch origin --prune`, `git fetch upstream --prune`) before reading
   anything else. Don't trust a cached mental model of where either remote sits.
2. **Independently resolve every relevant ref**, don't assume any of them from a prior conversation
   or document: current `origin/master`, current local `master`, current `upstream/master`, and the
   merge-base between `origin/master` and `upstream/master` (`git merge-base origin/master
   upstream/master`) — this merge-base is the last upstream commit already integrated, and everything
   after it on `upstream/master` is the new delta to analyze.
3. **Verify local `master` is not stale before branching from it.** A local branch ref can silently
   fall behind `origin/master` (nothing keeps it current automatically). Before treating local
   `master` as the sync's starting point:
   - Confirm no worktree currently has `master` checked out (`git worktree list`) — if one does, that
     worktree's contents are what would move, not just a ref, and need its own clean/dirty check.
   - Prove the relationship is a pure fast-forward: `git merge-base --is-ancestor master
     origin/master` must succeed. If it doesn't — local `master` carries commits `origin/master`
     doesn't have — stop and investigate before touching anything; don't force past that.
   - If it's a proven ancestor and unowned by any worktree, fast-forward the local ref directly
     without a checkout: `git fetch . origin/master:master`. This updates the ref in place and
     touches no working tree.
4. **Inspect every worktree** (`git worktree list`, then `git status --short` in each) before any
   operation that could be risky if run in the wrong place. This repo routinely runs several
   worktrees at once (see `docs/development-workflow.md`); a worktree that looks idle may be
   mid-recovery or holding another in-progress feature — never assume the worktree you're in is the
   only one that matters.
5. **Establish the pre-sync baseline**, on clean current `master`/`origin/master`, before anything else
   touches a tracked file: delete `build/classes` (and `build/test-classes` if present) to force a full
   recompile so stale compiled classes can't hide a real error, then run `ant test` and `ant jar`.
   **Never run `ant clean`; never remove `lib/ext`** — a source-level baseline doesn't need either, and
   both cost an unnecessary re-download from the H&H website. Record the pass/fail result in the
   Phase-1 dossier. This is the only way to later tell a post-merge failure from one that was already
   there — without it, a pre-existing failure gets misread as merge damage.
6. **Analyze only the new divergence** — the commits on `upstream/master` since the merge-base found
   in step 2. Don't re-derive analysis already recorded for a previously-integrated range; that
   belongs to a past `docs/upstream-sync-history/` entry, not this cycle's work.
7. For each new upstream commit: group into logical changes, list files touched, and intersect that
   file list against every file the fork has modified since the same merge-base. **This intersection is
   the primary shared-edit candidate set — not, by itself, a complete guarantee of everything that
   could conflict or interact.** A same-path filename match is the common case, but also account for:
   renames, delete/modify pairs, rename+modify, add/add collisions where both sides create a
   same-named file independently, and any path change git's diff/rename detection reports rather than
   a plain modify. Beyond textual overlap entirely, watch for **semantic dependencies with no shared
   path at all** — upstream changing the behavior of a file the fork never touched but whose behavior a
   fork feature silently depends on. Treat git's own diff/rename metadata and the disposable trial
   merge (next step) as the authority on actual textual conflict behavior, not a manual reading of two
   file lists.
8. **Run a disposable trial merge** for real conflict signal rather than relying solely on static
   three-way diff prediction (`git merge-tree`, or manual reasoning about hunk proximity) — those can
   flag a file as "changed in both" even when the actual edits don't collide. Create a throwaway
   branch from `origin/master`, run `git merge --no-commit --no-ff upstream/master` on it, record
   what actually happens (conflict markers or none), then `git merge --abort` and delete the branch.
   Nothing from a trial merge should persist past the Analyze phase.
9. **Consult `docs/fork-customization-ledger.md`** for every file the intersection touches. It records
   *why* each fork invariant exists and the minimum hook that must survive — read it before assuming a
   clean auto-merge in one of those files is actually safe, and before resolving a real conflict there
   by hand.
10. Check whether upstream's new commits touch any of the fork's sensitive integration seams (listed in
    the ledger) even where no textual conflict is possible — a file the fork hasn't edited can still
    change behavior the fork's parallel implementation depends on (the semantic-dependency case from
    step 7 again, specifically for known fork seams).
11. **Record the exact target upstream commit for this cycle** — resolve and write down
    `git rev-parse upstream/master` as this cycle's pinned target SHA (conceptually `TARGET_UPSTREAM_SHA`;
    the exact variable name doesn't matter, pinning it does). Everything from here on merges against
    *this* SHA, not against whatever `upstream/master` happens to point to later — upstream can receive
    new pushes mid-cycle, and without a pinned target, "did the merge integrate what Phase 1 analyzed"
    becomes ambiguous.
12. Produce a Phase-1 analysis artifact covering: verified refs, the pinned target SHA, the pre-sync
    baseline result, the new commit delta (grouped), fork/upstream file overlap (with the caveats from
    step 7), trial-merge result, ledger cross-check, and anything upstream now does that could supersede
    a fork invariant. Stop here for review before touching any tracked file.

### Phase 2 — Resolve / stage / pre-commit review

1. **Repo-local Git config** (safe to set once, don't need to repeat per sync — check first with
   `git config --local --get <key>` and only set what's missing):
   - `git config --local rerere.enabled true` — records how a conflict hunk was resolved by hand and
     replays that resolution automatically the next time the same hunk recurs. Genuinely useful across
     repeated syncs of the same recurring shared files.
   - `git config --local rerere.autoupdate false` — **correction to a common misreading:** this does
     not mean rerere reuses nothing. A remembered resolution is still applied to the *working tree*
     automatically. What `autoupdate=false` prevents is git *staging* that reused resolution into the
     index on your behalf. Any file rerere touches must still be manually reviewed and explicitly
     `git add`ed before it becomes part of the commit — never assume a rerere-resolved file is
     correct just because it applied cleanly.
   - `git config --local merge.conflictstyle zdiff3` — adds the merge-base text to conflict markers,
     so a real conflict shows what each side actually changed relative to the common ancestor, not
     just the two end states.
2. **Ensure the worktree you're about to use is clean.** Prefer a worktree with nothing else in
   progress; if reusing one, `git status --short` first.
3. **Create a collision-resistant safety tag** from the current pre-sync `master`, before creating the
   sync branch: a name containing both the date and the pre-sync short SHA (e.g.
   `pre-upstream-sync-YYYY-MM-DD-<short-sha>`) — the short SHA makes the tag name unambiguous even if
   more than one sync starts on the same calendar date. Verify it points exactly where expected.
4. **Create a new sync branch from current `master`** — never reuse a previous cycle's sync branch,
   even if it looks finished; a stale branch name invites confusion about which cycle's history it
   holds.
5. **Re-fetch `upstream` and re-check the pinned target SHA from Phase 1, step 11, before merging.** If
   `git rev-parse upstream/master` no longer equals the recorded `TARGET_UPSTREAM_SHA` — meaning
   upstream received new commits since Analyze — **stop and re-analyze the newly-added delta** before
   proceeding; don't fold an un-analyzed delta into an already-planned merge. Once confirmed unchanged,
   proceed against that exact SHA.
6. **Merge with `git merge --no-commit --no-ff upstream/master`** (equivalently, merge the pinned
   `TARGET_UPSTREAM_SHA` directly — they're identical once step 5 has confirmed the ref hasn't moved).
   Never rebase or cherry-pick across this boundary — rebasing rewrites commit hashes, so the next sync
   would see upstream's already-integrated commits as new again, causing the same conflicts to
   resurface indefinitely. A merge commit joins the two lines once and git remembers it did.
7. **Never blanket-resolve with `-X ours`/`-X theirs` or `--strategy-option`.** Every real conflict
   gets resolved by reading both sides and deciding what the merged result should actually say.
8. **Semantic review is required even when the merge reports zero textual conflicts.** A clean
   auto-merge only proves the edited line ranges didn't overlap — it proves nothing about whether the
   combined result is *correct*. For every file in the overlap set found in Analyze, manually inspect
   the merged result: are all expected fork entries and all expected upstream entries present, with no
   silent drops and no accidental duplicates or semantic collisions? For a shared enum/registry file
   specifically, count entries before and after and diff the two counts.
9. **If rerere reused a resolution, treat that file with extra scrutiny, not less.** Identify exactly
   which file it touched, read the resulting diff in full, and only `git add` it after confirming by
   hand that the replayed resolution is still correct for the current pair of changes — a resolution
   that was right for a past conflict is not guaranteed right for a superficially similar one.
10. **Forced full recompile, without `ant clean`.** Delete `build/classes` (and `build/test-classes` if
    present) to force a full recompile against the merged source — this is enough; a source-level
    change doesn't require wiping `lib/ext`. **Never run `ant clean`** for a routine sync — it deletes
    `lib/ext` entirely, forcing a full re-download of JOGL/LWJGL/steamworks jars from the H&H website,
    which is unnecessary cost for a source-only change and a needless dependency on that site being
    reachable. **Never remove `lib/ext` directly either**, for the same reason.
11. Run, in order, and record every result, on the staged (still uncommitted) merge: `ant test`,
    `ant jar`, `ant bin`.
    - **`ant bin` assembles/packages** the runnable client directory (and may fetch missing build
      dependencies it needs to do that) — it does **not** launch or log into the game.
    - **`ant run` is the target that actually launches the client** — a separate, heavier step,
      appropriate once a build is trusted enough to smoke-test interactively, not part of the routine
      build gate.
12. **Static integrity checks** on the staged merge: `git diff --check` (whitespace/conflict-marker
    errors), a direct conflict-marker grep as a second check, `git status`, `git diff --cached --stat`
    and `--name-status` (cross-check the staged file set against Phase 1's analyzed file list — it
    should match exactly, per the caveats in Phase 1 step 7).
13. **Produce a pre-commit review artifact** before concluding the merge: refreshed ref verification
    (confirms step 5 again), the actual merge result, the semantic review of every overlap file,
    confirmation every sensitive seam in the ledger shows zero diff against pre-sync `master`, and the
    full build/test/static-check results above. Stop here for review before committing.

### Phase 3 — Commit / PR / runtime verify

1. Conclude the merge with a real two-parent commit (`git commit`, no message flags that would turn it
   into anything other than the pending merge) — don't manufacture a merge commit by hand, and don't
   squash the two histories into one. Verify immediately: exactly two parents, in the order pre-sync
   `master` then the pinned `TARGET_UPSTREAM_SHA`; `git merge-base --is-ancestor
   <TARGET_UPSTREAM_SHA> HEAD` succeeds (verify against the *recorded* target from Phase 1/2, not
   whatever `upstream/master` has moved to since — that ref can have advanced again after Phase 2's
   merge and before this commit); `MERGE_HEAD` no longer exists; working tree clean; the safety tag and
   `master` both remain exactly where they were before the merge.
2. **Rebuild `ant bin` again after the commit exists**, when exact build attribution matters (e.g. the
   JAR is about to be used as the actual runtime-test candidate). The build system reads the embedded
   `buildinfo.git-rev` from `git rev-parse HEAD` at build time — a JAR built while the merge was still
   uncommitted embeds the *pre-merge* HEAD, not the final merge commit, even though its compiled
   contents are already correct and don't need recompiling. Verify the embedded value
   (`buildinfo.git-rev` inside the jar) matches the new merge commit SHA exactly before treating that
   JAR as canonical.
3. Push the sync branch and the safety tag to `origin`. Never push or alter `master` directly — the
   only path onto `master` is a reviewed, approved PR.
4. Open (or update) a PR: base `master`, head the sync branch. State in the description what was
   integrated, the merge result, the overlap-file review, build/test results, which fork invariants
   were confirmed untouched, and what remains deliberately deferred. Do not merge it yet.
5. **Target runtime verification at what the delta actually touches**, derived from the file-overlap
   and grouped-change analysis from Phase 1 — not a blind full regression pass. A bot family upstream
   reworked gets an in-client smoke test; a fork feature area upstream's commits never touched gets at
   most a quick sanity check, not a full pass. Stop here — before master promotion — until that
   verification (and any requested review) is complete.

Distinguish four separate validation moments through this whole process — conflating them produces
false confidence: the **pre-sync baseline** (Phase 1, step 5 — build/test on `master` before touching
anything, so a pre-existing failure is never mistaken for merge damage), the **disposable trial-merge
validation** (Phase 1's throwaway signal, discarded immediately), the **real staged-merge validation**
(Phase 2's build/test on the actual uncommitted merge that will become the commit), and the
**exact-commit runtime build** (Phase 3's post-commit `ant bin` rebuild, the only one with correct
`buildinfo` attribution).

### Phase 4 — Promote to master / release / finalize history

1. Only after the PR is reviewed and approved: merge it to `master`.
2. Verify `master`'s resulting state and ancestry (the merge commit is now reachable from `master`;
   `master` matches what the approved PR contained).
3. Only after `master` reflects the approved state: cut a release, if one is warranted, per
   `docs/release-process.md`.
4. Finalize the cycle's `docs/upstream-sync-history/<date>.md` entry — the durable facts (refs, the
   pinned target SHA this cycle actually merged against, merge commit, PR number, what was reviewed,
   build/test results, safety tag, deferred work). This is what the *next* sync's Analyze phase reads
   to know where the last one left off; keep it factual and dated, and never treat it as a place to
   restate `fork-customization-ledger.md`'s ongoing invariants.

## What never changes across cycles

- Merge-only across this fork boundary. Never rebase, never cherry-pick.
- A safety tag exists before any real merge is attempted.
- Shared registry/config/i18n files (the ones that recur in `docs/fork-customization-ledger.md`) are
  the fork's standing conflict watch-list — not a fixed list of filenames frozen at any one sync, since
  which files actually overlap can change cycle to cycle. Re-derive the current overlap in Analyze
  every time.
- Keep fork additions to those shared files small and localized (one new enum entry, one new registry
  line) rather than reformatted — small diffs merge clean; reformatted blocks don't.
- A clean auto-merge is a textual fact, not a correctness proof. Semantic review is not optional.

## Low-conflict development policy for new fork work

This governs how *new* fork functionality gets built going forward — it is not retroactive
authorization to refactor existing code during any particular sync.

**Fork-owned implementation, narrow shared hook.** Prefer building new functionality as fork-owned
classes/services/adapters over editing a file upstream also maintains. Where fork behavior must alter
something upstream owns, prefer composition or an adapter over copying and modifying upstream's
implementation wholesale. Where a shared file genuinely needs a fork-specific change, keep that change
to the smallest integration hook that does the job — the difference between a one-line addition and a
restructured block is often the difference between a clean auto-merge and a conflict.

Concretely:

- Don't reformat or restructure a shared registry/config/i18n file for style; every unrelated line
  changed is a line that can collide with upstream's own next change to that file.
- Centralize registrations (one registry, one place new entries go) rather than scattering
  fork-specific registration logic across multiple files.
- Preserve explicit session ownership — pass or store the owning session/UI object rather than
  resolving one ambiently.
- Avoid new ambient/global UI or session lookups (e.g. reaching for a global "current" accessor) where
  an owning object is already available in scope; ambient lookups are exactly what breaks silently
  when upstream changes session/lifecycle behavior underneath them with no textual conflict at all.
- Add integration-boundary tests where practical — a test at the seam between fork code and a shared
  upstream file catches a semantic break that a clean merge won't.

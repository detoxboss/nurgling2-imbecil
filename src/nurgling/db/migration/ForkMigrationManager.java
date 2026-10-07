package nurgling.db.migration;

import nurgling.db.DatabaseAdapter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Fork-owned schema migrations, tracked in a ledger of this fork's own.
 *
 * <h2>Why this exists as a separate manager</h2>
 *
 * {@link MigrationManager} and its {@code schema_version} table are the <b>upstream migration
 * namespace</b>. Its positive version sequence is upstream Nurgling2's to extend, and this fork
 * claimed a number in it twice for fork-specific schema: once for {@code stack_sizes} as version 13
 * (which collided with upstream's {@code quest_shares} 13), and then again for the compatibility
 * bridge as version 15 (which collided with upstream's {@code forage_finds} 15). Each collision cost
 * a hand-written bridge migration and, the first time, a coordinated upgrade of every client sharing
 * a database. The pattern is structural: any positive number this fork takes is a number upstream
 * will eventually reach.
 *
 * <p>So fork-specific migrations stop taking them. The precise invariant is that <b>fork-specific
 * migrations never claim or advance the upstream-owned positive {@code schema_version}
 * namespace</b> - the merged client still runs upstream's own numbered migrations and records each
 * of those in {@code schema_version}, which is that table's job. Fork-only schema is recorded here
 * instead, in {@value #LEDGER_TABLE}, keyed by a stable descriptive string id, and <b>nothing in
 * this class writes to {@code schema_version} at all</b>.
 *
 * <h2>What that buys</h2>
 *
 * <ul>
 *   <li>{@code MAX(schema_version.version)} keeps reporting exactly what a plain upstream client
 *       would report. {@link MigrationManager#runMigrations()} refuses a database whose recorded
 *       version exceeds {@link MigrationManager#CLIENT_MAX_SCHEMA_VERSION}, so leaving that number
 *       alone is what lets a released fork v1.0.9 client (max 15) and a released upstream client
 *       (max 15) keep using a database this client has migrated, instead of throwing
 *       {@link MigrationManager.SchemaTooNewException} at it.</li>
 *   <li>Fork tables are additive. An upstream client never looks for {@code stack_sizes} or for
 *       this ledger, and nothing in upstream's migration path drops a table it does not know
 *       about - it only ever creates. Unknown tables are inert to it.</li>
 *   <li>The next upstream migration (16, 17, ...) applies cleanly with no bridge, because this fork
 *       is no longer standing on the number.</li>
 * </ul>
 *
 * <h2>Standing rule for future fork schema</h2>
 *
 * Fork database extensions are <b>additive and isolated</b>. A new fork-only object gets a
 * {@code fork_} or {@code h4d_} prefixed name and a migration in this ledger - never a number in
 * upstream's positive {@code schema_version} sequence, and never a change to the <i>semantics</i> of
 * a table upstream owns, absent a compelling compatibility reason. {@code stack_sizes} keeps its
 * unprefixed name because released clients already read and write it; renaming it would strand their
 * data for no gain.
 *
 * @see MigrationManager
 */
public class ForkMigrationManager {

    /** This fork's own migration ledger. Never {@code schema_version}. */
    public static final String LEDGER_TABLE = "fork_schema_migrations";

    /**
     * Convergence migration: make every structure both schema lineages expect exist, whichever
     * subset this database already has, then bring generated {@code stack_sizes} seed data back in
     * line with the current static table.
     *
     * <p>The id is a stable string and must never be changed once released - it is the only record
     * that this work has already been done on a given database.
     */
    public static final String MIGRATION_STACK_SIZES_BRIDGE = "0001-stack-sizes-lineage-bridge";

    private final Connection connection;
    private final DatabaseAdapter adapter;

    public ForkMigrationManager(Connection connection, DatabaseAdapter adapter) {
        this.connection = connection;
        this.adapter = adapter;
    }

    /**
     * Apply every fork migration this database has no ledger row for.
     *
     * <p>Every fork migration is optional by construction: each one backs a fork feature that
     * already degrades to a local fallback, and none of them is allowed to be the reason the rest
     * of the database stops working. A failure therefore never propagates - it is rolled back,
     * <b>not</b> recorded, reported in the returned map, and retried on the next start once
     * whatever blocked it (almost always a missing {@code CREATE} grant) is fixed.
     *
     * @return the fork migrations that could not be applied, as id -> reason. Empty means the
     *         fork-side schema is fully up to date.
     */
    public Map<String, String> runForkMigrations() {
        Map<String, String> skipped = new LinkedHashMap<>();
        List<ForkMigration> migrations = getForkMigrations();

        if (!ensureLedgerTable()) {
            /* No ledger means no way to record success. Running the migrations anyway would redo
             * their DDL on every single start with no record that it had ever worked, so nothing is
             * attempted - and in practice a role that cannot create the ledger cannot create the
             * fork tables either. Every fork migration is reported as skipped, nothing is stamped,
             * and the attempt repeats next start. Fork tables that already exist stay usable:
             * DatabaseManager gates each feature on tableUsable(), not on this map. */
            String reason = "cannot create or read " + LEDGER_TABLE
                + " (needs CREATE on schema public, or the grants repaired)";
            for (ForkMigration m : migrations) {
                skipped.put(m.id, reason);
            }
            System.err.println("[ForkMigrationManager] " + reason
                + "; fork schema left as-is and will be retried on the next start");
            return skipped;
        }

        System.out.println("[ForkMigrationManager] fork migrations available: " + migrations.size());
        for (ForkMigration migration : migrations) {
            boolean applied;
            try {
                applied = isApplied(migration.id);
            } catch (SQLException e) {
                rollbackQuietly();
                skipped.put(migration.id, e.getMessage());
                System.err.println("[ForkMigrationManager] could not read ledger row for "
                    + migration.id + ": " + e.getMessage());
                break;
            }
            if (applied) {
                continue;
            }

            System.out.println("[ForkMigrationManager] running fork migration " + migration.id
                + ": " + migration.description);
            try {
                migration.run(adapter);
                recordApplied(migration.id);
                connection.commit();
                System.out.println("[ForkMigrationManager] fork migration " + migration.id
                    + " completed successfully");
            } catch (SQLException e) {
                rollbackQuietly();
                skipped.put(migration.id, e.getMessage());
                System.err.println("[ForkMigrationManager] fork migration " + migration.id
                    + " failed: " + e.getMessage());
                /* Nothing after it may run, for the same reason the upstream loop stops: a later
                 * success must not make this failure look handled. */
                System.err.println("[ForkMigrationManager] later fork migrations deferred until "
                    + migration.id + " succeeds");
                break;
            }
        }
        return skipped;
    }

    /**
     * Create the ledger if it is not already there.
     *
     * @return true when the ledger is present and readable by this role; false when it could not be
     *         created or read, which is an optional-feature failure and never fatal
     */
    private boolean ensureLedgerTable() {
        try {
            if (adapter.tableExists(LEDGER_TABLE)) {
                /* Present - but on PostgreSQL "present" came from information_schema, which filters
                 * by privilege, so this role can see it. Prove it can read it before trusting it. */
                try (ResultSet rs = adapter.executeQuery(
                        "SELECT id FROM " + LEDGER_TABLE + " WHERE id = ?", "")) {
                    rs.next();
                }
                return true;
            }
        } catch (SQLException e) {
            rollbackQuietly();
            System.err.println("[ForkMigrationManager] " + LEDGER_TABLE
                + " exists but is not readable by this role: " + e.getMessage());
            return false;
        }

        try {
            MigrationManager.createTable(adapter, LEDGER_TABLE,
                "CREATE TABLE " + LEDGER_TABLE + " (" +
                /* A stable descriptive string, not a number - see the class comment. */
                "id VARCHAR(128) PRIMARY KEY, " +
                "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                /* Who applied it, for a village host reading the table by hand. */
                "applied_by VARCHAR(255)" +
                ")");
            connection.commit();
            System.out.println("Created " + LEDGER_TABLE + " table");
            return true;
        } catch (SQLException e) {
            rollbackQuietly();
            if (MigrationManager.isAlreadyExists(e)) {
                /* Another client created it between the check and the CREATE, or it exists under a
                 * role whose privileges hide it from us. The first case is fine; the second is a
                 * grant problem this client cannot fix, and the read below settles which it is. */
                try (ResultSet rs = adapter.executeQuery(
                        "SELECT id FROM " + LEDGER_TABLE + " WHERE id = ?", "")) {
                    rs.next();
                    return true;
                } catch (SQLException read) {
                    rollbackQuietly();
                    System.err.println("[ForkMigrationManager] " + LEDGER_TABLE
                        + " already exists but cannot be read: " + read.getMessage());
                    return false;
                }
            }
            System.err.println("[ForkMigrationManager] could not create " + LEDGER_TABLE
                + ": " + e.getMessage());
            return false;
        }
    }

    private boolean isApplied(String id) throws SQLException {
        try (ResultSet rs = adapter.executeQuery(
                "SELECT id FROM " + LEDGER_TABLE + " WHERE id = ?", id)) {
            return rs.next();
        }
    }

    private void recordApplied(String id) throws SQLException {
        adapter.executeUpdate(
            "INSERT INTO " + LEDGER_TABLE + " (id, applied_by) VALUES (?, ?)", id, appliedBy());
    }

    /**
     * Best-effort attribution for a village host reading the ledger by hand. Never the reason a
     * migration fails, and never load-bearing: migrations run before login just as happily, in
     * which case this is simply null.
     */
    private static String appliedBy() {
        try {
            if (nurgling.NUtils.getUI() != null && nurgling.NUtils.getUI().sess != null
                && nurgling.NUtils.getUI().sess.user != null) {
                String name = nurgling.NUtils.getUI().sess.user.name;
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception | LinkageError ignore) {
            /* Reading a session must never be what breaks a migration. */
        }
        return null;
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignore) {
            /* Already broken; nothing useful left to do here. */
        }
    }

    /* ---------------- The fork migration list ---------------- */

    private List<ForkMigration> getForkMigrations() {
        List<ForkMigration> migrations = new ArrayList<>();

        /* Convergence. Three independently-shipped lineages can all report positive version 15
         * while holding different structures:
         *
         *   A. released fork v1.0.9  - quest_shares, timers, stack_sizes; NO forage_finds
         *   B. released upstream     - quest_shares, timers, forage_finds; NO stack_sizes
         *   C. fresh                 - everything upstream's 1..15 creates, including forage_finds
         *   D. historical fork v13   - stack_sizes only, positive version 13
         *
         * MigrationManager.runMigrations() only runs a migration strictly above the recorded
         * version, so on lineage A upstream's migration 15 will never run again and forage_finds
         * would stay missing forever. This closes that, in both directions, from any of them - and
         * unlike the version-15 bridge it replaces, it does so without touching schema_version, so
         * the positive version stays exactly 15 and older clients keep accepting the database.
         *
         * Deliberately NOT written as per-step try/catch. Swallowing a SQLException here would let
         * the ledger record this migration even though a structure was never created, burying the
         * missing one for good, and on PostgreSQL would carry on inside an already-aborted
         * transaction. Every step lets its SQLException propagate: runForkMigrations() rolls back,
         * records nothing, reports it, and retries on the next start. */
        migrations.add(new ForkMigration(MIGRATION_STACK_SIZES_BRIDGE,
                "Converge schema lineages: ensure quest_shares, timers, forage_finds and stack_sizes") {
            @Override
            void run(DatabaseAdapter adapter) throws SQLException {
                /* Upstream DDL, reused rather than copied - one definition per table, in
                 * MigrationManager, so a future upstream edit to any of them cannot leave this
                 * creating a stale shape. */
                if (!adapter.tableExists("quest_shares")) {
                    MigrationManager.createQuestSharesTable(adapter);
                }
                if (!adapter.tableExists("timers")) {
                    MigrationManager.createTimersTable(adapter);
                }
                /* Idempotent internally; the guard lives in the creator. */
                MigrationManager.createForageFindsTable(adapter);

                /* Fork DDL. */
                if (!adapter.tableExists("stack_sizes")) {
                    createStackSizesTable(adapter);
                    seedStackSizes(adapter);
                } else {
                    reconcileStackSizes(adapter);
                }
            }
        });

        return migrations;
    }

    /* ---------------- Fork-owned DDL and data ---------------- */

    /**
     * This fork's stack_sizes DDL - the shared, self-correcting item stack-size table that
     * supersedes {@code nurgling.tools.StackSupporter}'s static table wherever a DB-backed answer is
     * available. Optional, like fish_locations: a role without CREATE must not lose area, planning
     * and recipe sync over it, because every caller already falls back to the static table.
     *
     * <p>Keeps its unprefixed name on purpose. Released clients already read and write
     * {@code stack_sizes}; a rename to {@code fork_stack_sizes} would strand their rows. New
     * fork-only tables should take the {@code fork_}/{@code h4d_} prefix - see the class comment.
     */
    static void createStackSizesTable(DatabaseAdapter adapter) throws SQLException {
        boolean pg = (adapter instanceof nurgling.db.PostgresAdapter);
        String boolType = pg ? "BOOLEAN" : "INTEGER";
        String boolDefault = pg ? "TRUE" : "1";

        MigrationManager.createTable(adapter, "stack_sizes",
            "CREATE TABLE stack_sizes (" +
            "profile VARCHAR(255) NOT NULL DEFAULT 'global', " +
            "name VARCHAR(255) NOT NULL, " +
            "max_stack INTEGER NOT NULL, " +
            "stackable " + boolType + " NOT NULL DEFAULT " + boolDefault + ", " +
            /* 'seed' | 'learned' | 'manual' - see StackSizeDao. Not a foreign-keyed enum
             * table: three fixed values, checked only in Java. */
            "provenance VARCHAR(32) NOT NULL DEFAULT 'seed', " +
            "version INTEGER NOT NULL DEFAULT 1, " +
            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
            "last_touched_by VARCHAR(255), " +
            "last_touched_at TIMESTAMP, " +
            "deleted_at TIMESTAMP, " +
            "PRIMARY KEY (profile, name)" +
            ")");
        MigrationManager.safeCreateIndex(adapter, "CREATE INDEX idx_ss_profile ON stack_sizes (profile)");
        MigrationManager.safeCreateIndex(adapter, "CREATE INDEX idx_ss_deleted ON stack_sizes (deleted_at)");
        System.out.println("Created stack_sizes table");
    }

    /**
     * First-time population of a freshly created stack_sizes, so the DB starts at least as good as
     * the built-in table.
     *
     * <p>Reads {@link nurgling.tools.StackSupporter#staticSeedSnapshot()} - the deliberately
     * <b>static-only</b> view - rather than the DB-aware {@code getFullStackSize()}, so seeding can
     * never depend on whether {@code StackSizeService} happens to be constructed yet. Seeded under
     * profile 'global' because the static table is not genus-specific.
     */
    static void seedStackSizes(DatabaseAdapter adapter) throws SQLException {
        boolean pg = (adapter instanceof nurgling.db.PostgresAdapter);
        Map<String, nurgling.tools.StackSupporter.StaticStackFact> snapshot =
            nurgling.tools.StackSupporter.staticSeedSnapshot();
        int seeded = 0;
        for (Map.Entry<String, nurgling.tools.StackSupporter.StaticStackFact> e : snapshot.entrySet()) {
            nurgling.tools.StackSupporter.StaticStackFact fact = e.getValue();
            Object stackableValue = pg ? fact.stackable : (fact.stackable ? 1 : 0);
            adapter.executeUpdate(
                "INSERT INTO stack_sizes (profile, name, max_stack, stackable, provenance, version) " +
                "VALUES ('global', ?, ?, ?, 'seed', 1)",
                e.getKey(), fact.maxStack, stackableValue);
            seeded++;
        }
        System.out.println("Seeded " + seeded + " rows into stack_sizes from the static table");
    }

    /**
     * Brings an existing stack_sizes table's <b>generated</b> rows back in line with the current
     * static table, and inserts rows for names the static table has newly learned about.
     *
     * <p>Necessary because {@link nurgling.tools.StackSupporter} consults the DB <i>first</i> and
     * only falls back to the static table when the DB has no opinion. A 'seed' row written by an
     * older client therefore <b>shadows</b> any correction a later client's static table carries,
     * and passive learning cannot undo that in general: {@code StackSizeDao.upsertIfBigger} only
     * ever raises {@code max_stack} (it can flip a wrongly-unstackable row to stackable once a real
     * stack is seen, but can never correct a max_stack downwards).
     *
     * <p>Rules, in order of precedence:
     * <ul>
     *   <li>{@code provenance='manual'} and {@code 'learned'} rows are never touched - a player's
     *       calibration and an actually-observed stack both outrank a generated guess.</li>
     *   <li>Tombstoned rows ({@code deleted_at IS NOT NULL}) are never resurrected and never
     *       updated. A tombstone is the calibration UI's "forget this row, go back to the static
     *       table" - which, post-merge, means the <i>new</i> static table. Resurrecting it would
     *       silently undo a deliberate player action.</li>
     *   <li>A live 'seed' row whose values no longer match the static table is updated, with
     *       {@code version = version + 1}. The version bump is load-bearing, not cosmetic:
     *       {@code StackSizeService.runDeltaPoll()} refetches a row only when the database's
     *       version exceeds its cached one, so a silent in-place update would never reach any other
     *       client sharing this database.</li>
     *   <li>{@code last_touched_by}/{@code last_touched_at} are deliberately left alone. They record
     *       <i>who</i> edited a row; a generated reconciliation has no human author, and
     *       overwriting them would misattribute this change to whoever last touched the row.</li>
     *   <li>A name the static table knows and the table has no row for at all - not even a
     *       tombstone - is inserted as a fresh 'seed' row.</li>
     * </ul>
     *
     * <p><b>Stale live seed rows absent from the current static snapshot are corrected in place,
     * not tombstoned.</b> Tombstoning on absence was considered and rejected: the snapshot is
     * best-effort by construction ({@code seedCandidateNames()} skips any category VSpec cannot
     * resolve, swallowing the error), so treating "absent from the snapshot" as "delete it" turns a
     * tolerant read into a destructive write that could wipe hundreds of valid rows the one time
     * VSpec is incomplete. Instead this recomputes the current static answer for every live seed row
     * by name - {@code isStackableByName}/{@code getFullStackSizeStatic} are pure functions over the
     * static tables and cannot throw - so a row whose name the static table no longer recognises is
     * simply rewritten to the fallback answer (unstackable, 1), which is behaviourally identical to
     * the row not existing, without any of the risk.
     */
    static void reconcileStackSizes(DatabaseAdapter adapter) throws SQLException {
        boolean pg = (adapter instanceof nurgling.db.PostgresAdapter);

        /* Every row's current state, read once. liveSeed holds the rows this may rewrite; present
         * holds every name with any row at all (tombstones and manual/learned included) so an
         * INSERT is only attempted for a name the table has never heard of. */
        Map<String, int[]> liveSeed = new LinkedHashMap<>();
        java.util.Set<String> present = new java.util.HashSet<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT name, max_stack, stackable, provenance, deleted_at FROM stack_sizes WHERE profile = 'global'")) {
            while (rs.next()) {
                String name = rs.getString("name");
                present.add(name);
                boolean live = (rs.getTimestamp("deleted_at") == null);
                if (live && "seed".equals(rs.getString("provenance"))) {
                    liveSeed.put(name, new int[] {rs.getInt("max_stack"), rs.getBoolean("stackable") ? 1 : 0});
                }
            }
        }

        /* Union of "what the static table knows now" and "what we already generated", so a seed row
         * for a name the static table has since dropped is still corrected rather than left stale. */
        LinkedHashSet<String> names = new LinkedHashSet<>(
            nurgling.tools.StackSupporter.staticSeedSnapshot().keySet());
        names.addAll(liveSeed.keySet());

        int updated = 0, inserted = 0;
        for (String name : names) {
            boolean stackable = nurgling.tools.StackSupporter.isStackableByName(name);
            int maxStack = stackable ? nurgling.tools.StackSupporter.getFullStackSizeStatic(name) : 1;
            int[] cur = liveSeed.get(name);
            if (cur != null) {
                if (cur[0] != maxStack || (cur[1] != 0) != stackable) {
                    adapter.executeUpdate(
                        "UPDATE stack_sizes SET max_stack = ?, stackable = ?, version = version + 1, " +
                        "updated_at = CURRENT_TIMESTAMP " +
                        "WHERE profile = 'global' AND name = ? AND provenance = 'seed' AND deleted_at IS NULL",
                        maxStack, pg ? stackable : (stackable ? 1 : 0), name);
                    updated++;
                }
            } else if (!present.contains(name)) {
                adapter.executeUpdate(
                    "INSERT INTO stack_sizes (profile, name, max_stack, stackable, provenance, version) " +
                    "VALUES ('global', ?, ?, ?, 'seed', 1)",
                    name, maxStack, pg ? stackable : (stackable ? 1 : 0));
                inserted++;
            }
            /* else: a manual, learned or tombstoned row - left exactly as it is. */
        }
        System.out.println("Reconciled stack_sizes against the static table: "
            + updated + " seed row(s) corrected, " + inserted + " added");
    }

    /** One fork migration. Identified by a stable string, never by a number. */
    public abstract static class ForkMigration {
        final String id;
        final String description;

        ForkMigration(String id, String description) {
            this.id = id;
            this.description = description;
        }

        abstract void run(DatabaseAdapter adapter) throws SQLException;
    }
}

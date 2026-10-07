package nurgling.db.migration;

import nurgling.db.DatabaseAdapter;
import nurgling.db.SqliteAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * End-to-end tests for the split migration architecture, against real, disposable SQLite databases
 * created per test under JUnit's temp directory. Nothing here touches a shared or production
 * database.
 *
 * <h2>What is being pinned</h2>
 *
 * {@code schema_version} is upstream-owned and its positive sequence is upstream's alone;
 * fork-only schema is recorded in {@code fork_schema_migrations} by
 * {@link ForkMigrationManager}. The properties that matter, and that these tests exist to hold:
 *
 * <ul>
 *   <li>{@code MAX(schema_version.version)} is <b>exactly 15</b> after everything runs, on every
 *       lineage. That number is what a released client compares against its own
 *       {@link MigrationManager#CLIENT_MAX_SCHEMA_VERSION}, so moving it is what breaks old
 *       clients.</li>
 *   <li>Every lineage converges structurally: a database missing {@code forage_finds} (released
 *       fork v1.0.9) gets it; one missing {@code stack_sizes} (released upstream) gets it,
 *       seeded.</li>
 *   <li>A fork migration that fails records <b>nothing</b> in the ledger and rolls back, so the
 *       next start retries rather than burying the missing structure.</li>
 *   <li>Generated seed rows may be reconciled; manual, learned and tombstoned rows may not.</li>
 * </ul>
 */
class MigrationLineageTest {

    // ---------------- harness ----------------

    private static Connection open(Path dir, String name) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(name));
        // Mirrors SimpleConnectionPool: the managers commit and roll back explicitly.
        conn.setAutoCommit(false);
        return conn;
    }

    /** Upstream's numbered migrations only - what a plain upstream client would run. */
    private static Map<Integer, String> upstreamMigrate(Connection conn) throws SQLException {
        DatabaseAdapter adapter = new SqliteAdapter(conn);
        Map<Integer, String> skipped = new MigrationManager(conn, adapter).runMigrations();
        conn.commit();
        return skipped;
    }

    /** The fork's own ledger-tracked migrations only. */
    private static Map<String, String> forkMigrate(Connection conn) throws SQLException {
        DatabaseAdapter adapter = new SqliteAdapter(conn);
        Map<String, String> skipped = new ForkMigrationManager(conn, adapter).runForkMigrations();
        conn.commit();
        return skipped;
    }

    /** Both, in the order DatabaseManager runs them. */
    private static void migrate(Connection conn) throws SQLException {
        assertTrue(upstreamMigrate(conn).isEmpty(), "unexpected skipped upstream migrations");
        assertTrue(forkMigrate(conn).isEmpty(), "unexpected skipped fork migrations");
    }

    private static int version(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static boolean tableExists(Connection conn, String name) throws SQLException {
        return new SqliteAdapter(conn).tableExists(name);
    }

    private static boolean forkApplied(Connection conn, String id) throws SQLException {
        return count(conn, "SELECT COUNT(*) FROM " + ForkMigrationManager.LEDGER_TABLE
            + " WHERE id = '" + id + "'") == 1;
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        }
        conn.commit();
    }

    private static int count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /**
     * A database in the SCHEMA SHAPE a released fork v1.0.9 client left it: positive version 15,
     * {@code quest_shares}/{@code timers}/{@code stack_sizes} present, {@code forage_finds} absent
     * (v1.0.9's migration 15 was the fork bridge, not upstream's forage table), and no fork ledger.
     *
     * <p>Built by running the current migrations and then removing what v1.0.9 would not have had -
     * so its seed data is this merge's current static table, not a byte-for-byte reproduction of
     * what an old fork build actually seeded. That distinction does not matter for what this
     * fixture proves (the version gate and structural convergence); the stale-seed-row tests below
     * cover old pre-correction seed data by overwriting specific rows explicitly.
     */
    private static Connection forkV109Lineage(Path dir, String name) throws SQLException {
        Connection conn = open(dir, name);
        migrate(conn);
        exec(conn, "DROP TABLE forage_finds");
        exec(conn, "DROP TABLE " + ForkMigrationManager.LEDGER_TABLE);
        return conn;
    }

    /**
     * A database in the state a released upstream client left it: positive version 15,
     * {@code forage_finds} present, {@code stack_sizes} absent, no fork ledger.
     */
    private static Connection upstreamLineage(Path dir, String name) throws SQLException {
        Connection conn = open(dir, name);
        migrate(conn);
        exec(conn, "DROP TABLE stack_sizes");
        exec(conn, "DROP TABLE " + ForkMigrationManager.LEDGER_TABLE);
        return conn;
    }

    /** The historical fork-v13 lineage: stack_sizes only, positive version 13. */
    private static Connection forkV13Lineage(Path dir, String name) throws SQLException {
        Connection conn = open(dir, name);
        migrate(conn);
        exec(conn, "DROP TABLE quest_shares");
        exec(conn, "DROP TABLE timers");
        exec(conn, "DROP TABLE forage_finds");
        exec(conn, "DROP TABLE " + ForkMigrationManager.LEDGER_TABLE);
        exec(conn, "DELETE FROM schema_version WHERE version > 13");
        return conn;
    }

    // ---------------- A: released fork v1.0.9 ----------------

    /**
     * The transitional case, and the whole reason the fork bridge still exists. A v1.0.9 database
     * already reads positive version 15, so {@link MigrationManager#runMigrations()} will never run
     * upstream's migration 15 on it again - {@code forage_finds} would stay missing forever. The
     * fork's convergence migration is what creates it, and it does so without moving the positive
     * version.
     */
    @Test
    void lineageA_releasedForkV109GainsForageFindsAndKeepsStackSizes(@TempDir Path dir) throws Exception {
        try (Connection conn = forkV109Lineage(dir, "a.db")) {
            assertEquals(15, version(conn));
            assertTrue(tableExists(conn, "stack_sizes"));
            assertTrue(tableExists(conn, "quest_shares"));
            assertTrue(tableExists(conn, "timers"));
            assertFalse(tableExists(conn, "forage_finds"));
            int seededBefore = count(conn, "SELECT COUNT(*) FROM stack_sizes");
            assertTrue(seededBefore > 100, "expected a populated seed, got " + seededBefore);

            // Upstream's own pass can do nothing here - it is already at its own maximum.
            assertTrue(upstreamMigrate(conn).isEmpty());
            assertFalse(tableExists(conn, "forage_finds"),
                "upstream's migration 15 cannot run on a database already stamped 15");

            assertTrue(forkMigrate(conn).isEmpty());

            assertTrue(tableExists(conn, "forage_finds"), "forage_finds must be created");
            assertTrue(tableExists(conn, "stack_sizes"), "stack_sizes must be preserved");
            assertTrue(count(conn, "SELECT COUNT(*) FROM stack_sizes") >= seededBefore,
                "reconciliation must not delete seed rows");
            assertEquals(15, version(conn), "the positive schema version must not move");
            assertTrue(forkApplied(conn, ForkMigrationManager.MIGRATION_STACK_SIZES_BRIDGE));
        }
    }

    // ---------------- B: released upstream lineage ----------------

    @Test
    void lineageB_upstreamDatabaseGainsStackSizesAndKeepsForage(@TempDir Path dir) throws Exception {
        try (Connection conn = upstreamLineage(dir, "b.db")) {
            assertEquals(15, version(conn));
            assertTrue(tableExists(conn, "quest_shares"));
            assertTrue(tableExists(conn, "timers"));
            assertTrue(tableExists(conn, "forage_finds"));
            assertFalse(tableExists(conn, "stack_sizes"));
            exec(conn, "INSERT INTO forage_finds (id, grid_id, ox, oy, gob_res, item_name, quality, found_at) "
                + "VALUES ('keepme', 1, 2, 3, 'gfx/terobjs/herbs/x', 'Yarrow', 10.0, 1)");

            assertTrue(upstreamMigrate(conn).isEmpty());
            assertTrue(forkMigrate(conn).isEmpty());

            assertTrue(tableExists(conn, "stack_sizes"), "stack_sizes must be created");
            assertTrue(count(conn, "SELECT COUNT(*) FROM stack_sizes") > 100, "stack_sizes must be seeded");
            // Upstream's tables and their data must be left alone.
            assertTrue(tableExists(conn, "quest_shares"));
            assertTrue(tableExists(conn, "timers"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM forage_finds WHERE id = 'keepme'"),
                "existing forage data must be preserved");
            assertEquals(15, version(conn), "the positive schema version must not move");
        }
    }

    // ---------------- C: fresh database ----------------

    @Test
    void lineageC_freshDatabaseCreatesEverythingExactlyOnce(@TempDir Path dir) throws Exception {
        try (Connection conn = open(dir, "c.db")) {
            migrate(conn);

            assertEquals(15, version(conn));
            assertTrue(tableExists(conn, "quest_shares"));
            assertTrue(tableExists(conn, "timers"));
            assertTrue(tableExists(conn, "forage_finds"));
            assertTrue(tableExists(conn, "stack_sizes"));
            assertTrue(tableExists(conn, ForkMigrationManager.LEDGER_TABLE));
            /* One row per applied upstream version, and nothing else. A fork migration leaking into
             * this table is exactly the regression this architecture exists to prevent, so count the
             * rows rather than only checking the maximum. */
            assertEquals(15, count(conn, "SELECT COUNT(DISTINCT version) FROM schema_version"));
            assertEquals(15, count(conn, "SELECT COUNT(*) FROM schema_version"));
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM schema_version WHERE version < 1"),
                "no fork migration may write a row into schema_version");
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM " + ForkMigrationManager.LEDGER_TABLE));
            assertEquals(count(conn, "SELECT COUNT(*) FROM stack_sizes"),
                count(conn, "SELECT COUNT(DISTINCT name) FROM stack_sizes"),
                "seeding must not duplicate names");
        }
    }

    // ---------------- D: historical fork-v13 lineage ----------------

    @Test
    void lineageD_forkV13LineageRepairsEverything(@TempDir Path dir) throws Exception {
        try (Connection conn = forkV13Lineage(dir, "d13.db")) {
            assertEquals(13, version(conn));
            assertTrue(tableExists(conn, "stack_sizes"));
            assertFalse(tableExists(conn, "quest_shares"));
            assertFalse(tableExists(conn, "timers"));
            assertFalse(tableExists(conn, "forage_finds"));
            // Player data that must survive the repair untouched.
            exec(conn, "UPDATE stack_sizes SET max_stack = 42, provenance = 'manual', version = 3 "
                + "WHERE name = 'Branch'");

            assertTrue(upstreamMigrate(conn).isEmpty());
            assertTrue(forkMigrate(conn).isEmpty());

            assertEquals(15, version(conn));
            assertTrue(tableExists(conn, "quest_shares"));
            assertTrue(tableExists(conn, "timers"));
            assertTrue(tableExists(conn, "forage_finds"));
            assertTrue(tableExists(conn, "stack_sizes"));
            assertEquals(42, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Branch'"),
                "a manual row must survive lineage repair");
            assertEquals(3, count(conn, "SELECT version FROM stack_sizes WHERE name = 'Branch'"));
        }
    }

    // ---------------- idempotent rerun ----------------

    @Test
    void rerunIsIdempotent(@TempDir Path dir) throws Exception {
        try (Connection conn = open(dir, "idem.db")) {
            migrate(conn);
            int rows = count(conn, "SELECT COUNT(*) FROM stack_sizes");
            int versionRows = count(conn, "SELECT COUNT(*) FROM schema_version");
            int ledgerRows = count(conn, "SELECT COUNT(*) FROM " + ForkMigrationManager.LEDGER_TABLE);

            migrate(conn);

            assertEquals(15, version(conn));
            assertEquals(rows, count(conn, "SELECT COUNT(*) FROM stack_sizes"));
            assertEquals(versionRows, count(conn, "SELECT COUNT(*) FROM schema_version"));
            assertEquals(ledgerRows, count(conn, "SELECT COUNT(*) FROM "
                + ForkMigrationManager.LEDGER_TABLE), "a ledger row must not be written twice");
        }
    }

    // ---------------- E: failure / retry ----------------

    /**
     * The safety property the whole fork ledger rests on. A fork migration that fails must NOT be
     * recorded - recording it would permanently bury whichever structure never got created - and
     * must be atomic, so anything it created before the failure rolls back and the next start
     * retries from a consistent state.
     *
     * <p>Failure is injected without touching production code, by renaming a column
     * {@code reconcileStackSizes}'s first SELECT depends on. That makes the migration fail
     * <em>after</em> it has already created forage_finds in the same transaction, which is exactly
     * the case where swallowing the exception per step (the design rejected in review) would have
     * stamped the ledger on a database that still had no forage_finds.
     */
    @Test
    void lineageE_forkFailureRecordsNothingAndRetriesCleanly(@TempDir Path dir) throws Exception {
        try (Connection conn = forkV109Lineage(dir, "e.db")) {
            assertEquals(15, version(conn));
            int seedRows = count(conn, "SELECT COUNT(*) FROM stack_sizes");

            exec(conn, "ALTER TABLE stack_sizes RENAME COLUMN max_stack TO max_stack_broken");

            Map<String, String> skipped = forkMigrate(conn);

            // Reported, not thrown: every fork migration is optional, so the client still comes up.
            assertTrue(skipped.containsKey(ForkMigrationManager.MIGRATION_STACK_SIZES_BRIDGE),
                "the fork migration should be reported skipped, got " + skipped);
            // Nothing recorded, so the next start retries.
            assertTrue(tableExists(conn, ForkMigrationManager.LEDGER_TABLE),
                "the ledger itself is created in its own transaction and survives");
            assertFalse(forkApplied(conn, ForkMigrationManager.MIGRATION_STACK_SIZES_BRIDGE),
                "a failed fork migration must not be recorded as applied");
            // Everything the migration itself did must have rolled back.
            assertFalse(tableExists(conn, "forage_finds"),
                "forage_finds was created inside the failed migration and must be rolled back");
            // Existing data untouched, and upstream's version untouched.
            assertTrue(tableExists(conn, "stack_sizes"));
            assertEquals(seedRows, count(conn, "SELECT COUNT(*) FROM stack_sizes"));
            assertEquals(15, version(conn), "a failed fork migration must not move the schema version");

            // Correct the failure condition; the next start must retry and succeed.
            exec(conn, "ALTER TABLE stack_sizes RENAME COLUMN max_stack_broken TO max_stack");

            Map<String, String> retry = forkMigrate(conn);

            assertTrue(retry.isEmpty(), "retry should not skip anything: " + retry);
            assertTrue(forkApplied(conn, ForkMigrationManager.MIGRATION_STACK_SIZES_BRIDGE));
            assertTrue(tableExists(conn, "forage_finds"));
            assertTrue(tableExists(conn, "stack_sizes"));
            assertEquals(15, version(conn));
        }
    }

    // ---------------- cross-client compatibility ----------------

    /**
     * A released v1.0.9 client (whose {@code CLIENT_MAX_SCHEMA_VERSION} is also 15) must be able to
     * keep using a database this merge has migrated. That hangs on exactly one number:
     * {@link MigrationManager#runMigrations()} throws {@link MigrationManager.SchemaTooNewException}
     * when the recorded version exceeds the client's maximum, and the fork ledger is what keeps the
     * recorded version at 15 instead of 16.
     */
    @Test
    void postMergeDatabaseIsStillAcceptedByAMax15Client(@TempDir Path dir) throws Exception {
        try (Connection conn = open(dir, "compat.db")) {
            migrate(conn);
            assertEquals(15, version(conn));
            assertTrue(version(conn) <= MigrationManager.CLIENT_MAX_SCHEMA_VERSION,
                "a client whose maximum is " + MigrationManager.CLIENT_MAX_SCHEMA_VERSION
                    + " would refuse version " + version(conn));

            /* The same code path a v1.0.9 / released-upstream client runs on connect. It must not
             * throw, must skip nothing, and must leave the fork's tables and ledger alone - nothing
             * in the numbered migration list drops a table, it only ever creates. */
            Map<Integer, String> asOldClient =
                assertDoesNotThrow(() -> upstreamMigrate(conn),
                    "an older client must not refuse this database");
            assertTrue(asOldClient.isEmpty(), "unexpected skipped migrations: " + asOldClient);
            assertEquals(15, version(conn));
            assertTrue(tableExists(conn, "stack_sizes"), "an older client must not drop fork tables");
            assertTrue(tableExists(conn, ForkMigrationManager.LEDGER_TABLE),
                "an older client must not drop the fork ledger");
            assertTrue(tableExists(conn, "forage_finds"));
            assertTrue(count(conn, "SELECT COUNT(*) FROM stack_sizes") > 100,
                "an older client must not clear fork data");
        }
    }

    /** The positive sequence stays upstream's: no fork migration may appear in schema_version. */
    @Test
    void forkMigrationsNeverTouchSchemaVersion(@TempDir Path dir) throws Exception {
        try (Connection conn = open(dir, "sep.db")) {
            assertTrue(upstreamMigrate(conn).isEmpty());
            int before = count(conn, "SELECT COUNT(*) FROM schema_version");
            int maxBefore = version(conn);

            assertTrue(forkMigrate(conn).isEmpty());

            assertEquals(before, count(conn, "SELECT COUNT(*) FROM schema_version"),
                "a fork migration added a row to the upstream-owned table");
            assertEquals(maxBefore, version(conn), "a fork migration moved the positive version");
            assertEquals(15, maxBefore);
            // And the fork's own ledger is keyed by a descriptive string, not an integer.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id FROM "
                     + ForkMigrationManager.LEDGER_TABLE)) {
                assertTrue(rs.next(), "expected a ledger row");
                String id = rs.getString(1);
                assertNotNull(id);
                assertFalse(id.matches("-?\\d+"), "fork migration ids must not be bare integers: " + id);
                assertEquals(ForkMigrationManager.MIGRATION_STACK_SIZES_BRIDGE, id);
            }
        }
    }

    // ---------------- seed reconciliation semantics ----------------

    /**
     * The convergence migration corrects generated ('seed') rows against the current static table,
     * and must never overwrite what a player calibrated ('manual') or a client actually observed
     * ('learned'), nor resurrect a row a player deleted (tombstoned).
     */
    @Test
    void reconciliationRespectsProvenanceAndTombstones(@TempDir Path dir) throws Exception {
        try (Connection conn = forkV109Lineage(dir, "r.db")) {
            // A stale generated row, as an older client's seed would have left it.
            exec(conn, "UPDATE stack_sizes SET max_stack = 99, stackable = 1, provenance = 'seed', "
                + "version = 7 WHERE name = 'Reeds'");
            // Player calibration and passive learning must both be untouchable.
            exec(conn, "UPDATE stack_sizes SET max_stack = 42, provenance = 'manual', version = 3 "
                + "WHERE name = 'Branch'");
            exec(conn, "UPDATE stack_sizes SET max_stack = 11, provenance = 'learned', version = 4 "
                + "WHERE name = 'Straw'");
            // A row the player deleted: a tombstone means "fall back to the static table".
            exec(conn, "UPDATE stack_sizes SET deleted_at = CURRENT_TIMESTAMP, provenance = 'seed', "
                + "version = 5 WHERE name = 'Yarrow'");
            // last_touched_by must survive a generated correction - it records a person, and this
            // change has no human author.
            exec(conn, "UPDATE stack_sizes SET last_touched_by = 'someone' WHERE name = 'Reeds'");

            assertTrue(forkMigrate(conn).isEmpty());
            assertEquals(15, version(conn));

            // Stale seed row corrected, and its version bumped so other clients' delta poll sees it.
            assertEquals(4, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Reeds'"));
            assertEquals(8, count(conn, "SELECT version FROM stack_sizes WHERE name = 'Reeds'"));
            assertEquals(1, count(conn,
                "SELECT COUNT(*) FROM stack_sizes WHERE name = 'Reeds' AND last_touched_by = 'someone'"));

            // Manual and learned rows untouched, versions not bumped.
            assertEquals(42, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Branch'"));
            assertEquals(3, count(conn, "SELECT version FROM stack_sizes WHERE name = 'Branch'"));
            assertEquals(11, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Straw'"));
            assertEquals(4, count(conn, "SELECT version FROM stack_sizes WHERE name = 'Straw'"));

            // Tombstone neither resurrected nor rewritten.
            assertEquals(1, count(conn,
                "SELECT COUNT(*) FROM stack_sizes WHERE name = 'Yarrow' AND deleted_at IS NOT NULL"));
            assertEquals(5, count(conn, "SELECT version FROM stack_sizes WHERE name = 'Yarrow'"));
        }
    }

    /**
     * An upstream-lineage database gets its stack_sizes seeded from the <em>current</em> static
     * table, so the corrections upstream shipped are present from the start.
     */
    @Test
    void upstreamLineageSeedCarriesCurrentStaticFacts(@TempDir Path dir) throws Exception {
        try (Connection conn = upstreamLineage(dir, "s.db")) {
            assertTrue(forkMigrate(conn).isEmpty());
            assertEquals(2, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Brain'"));
            assertEquals(3, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Small Brain'"));
            assertEquals(4, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Lynx Claws'"));
            assertEquals(1, count(conn,
                "SELECT stackable FROM stack_sizes WHERE name = 'Lynx Claws'"));
            // The " Meat" category-key fix: weird meats must reach the seed set at all.
            assertEquals(5, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Ant Meat'"));
        }
    }

    /**
     * A fork-lineage database seeded by an <em>older</em> client carries the pre-correction values;
     * reconciliation is the only thing that can fix them, because StackSizeService's lookup
     * overrides the static table and passive learning can never lower a max_stack.
     */
    @Test
    void reconciliationFixesStaleGeneratedFactsFromAnOlderClient(@TempDir Path dir) throws Exception {
        try (Connection conn = forkV109Lineage(dir, "o.db")) {
            // What an older fork client's seed produced: Lynx Claws was a catExceptions entry.
            exec(conn, "UPDATE stack_sizes SET max_stack = 1, stackable = 0, provenance = 'seed' "
                + "WHERE name = 'Lynx Claws'");
            // And "Brain" took its category's size rather than the corrected custom size.
            exec(conn, "UPDATE stack_sizes SET max_stack = 4, stackable = 1, provenance = 'seed' "
                + "WHERE name = 'Brain'");

            assertTrue(forkMigrate(conn).isEmpty());

            assertEquals(4, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Lynx Claws'"));
            assertEquals(1, count(conn, "SELECT stackable FROM stack_sizes WHERE name = 'Lynx Claws'"));
            assertEquals(2, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Brain'"));
        }
    }

    /**
     * CLIENT_MAX_SCHEMA_VERSION must match the highest UPSTREAM migration. The fork no longer
     * contributes to this number at all, which is the point - if a future sync makes this fail,
     * upstream has added a migration and the constant simply follows it.
     */
    @Test
    void clientMaxSchemaVersionMatchesHighestUpstreamMigration() {
        assertEquals(15, MigrationManager.CLIENT_MAX_SCHEMA_VERSION);
        assertEquals(13, MigrationManager.MIGRATION_QUEST_SHARES);
        assertEquals(14, MigrationManager.MIGRATION_TIMERS);
        assertEquals(15, MigrationManager.MIGRATION_FORAGE_FINDS);
    }
}

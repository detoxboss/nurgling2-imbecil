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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the fork/upstream schema-lineage bridge (migration 15) against real,
 * disposable SQLite databases created per test under JUnit's temp directory. Nothing here touches a
 * shared or production database.
 *
 * <p>Two incompatible version-13 schemas shipped independently: this fork's 13 created
 * {@code stack_sizes}; upstream's 13 created {@code quest_shares} and its 14 created {@code timers}.
 * {@link MigrationManager#runMigrations()} only runs migrations strictly above the recorded
 * version, so a fork database stamped 13 would skip upstream's 13 forever. Migration 15 is the
 * bridge. These tests pin every lineage it has to survive, and - most importantly - that a failure
 * inside it never records version 15 with a structure missing.
 */
class MigrationLineageTest {

    // ---------------- harness ----------------

    private static Connection open(Path dir, String name) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(name));
        // Mirrors SimpleConnectionPool: runMigrations() commits and rolls back explicitly.
        conn.setAutoCommit(false);
        return conn;
    }

    private static Map<Integer, String> migrate(Connection conn) throws SQLException {
        DatabaseAdapter adapter = new SqliteAdapter(conn);
        Map<Integer, String> skipped = new MigrationManager(conn, adapter).runMigrations();
        conn.commit();
        return skipped;
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
     * A database in the SCHEMA SHAPE a released fork client left it: version 13, stack_sizes,
     * no quest_shares/timers. Built by running the current migrations and then dropping the two
     * upstream tables - so its seed data is this merge's current static table (815 rows), not a
     * byte-for-byte reproduction of what an old fork build actually seeded. That distinction does
     * not matter for what this fixture is used to prove (the version-gate skip and the structural
     * convergence); the separate stale-seed-row tests below (reconciliationFixesStaleGeneratedFactsFromAnOlderClient,
     * reconciliationRespectsProvenanceAndTombstones) are what cover old, pre-correction seed data,
     * by overwriting specific rows explicitly rather than relying on this fixture's shape.
     */
    private static Connection forkLineage(Path dir, String name) throws SQLException {
        Connection conn = open(dir, name);
        migrate(conn);
        exec(conn, "DROP TABLE quest_shares");
        exec(conn, "DROP TABLE timers");
        exec(conn, "DELETE FROM schema_version WHERE version > 13");
        return conn;
    }

    /** A database in the state a released upstream client left it: version 14, no stack_sizes. */
    private static Connection upstreamLineage(Path dir, String name) throws SQLException {
        Connection conn = open(dir, name);
        migrate(conn);
        exec(conn, "DROP TABLE stack_sizes");
        exec(conn, "DELETE FROM schema_version WHERE version > 14");
        return conn;
    }

    // ---------------- A: released fork schema shape ----------------

    /**
     * Proves the version-gate/structural-convergence behavior for a database shaped like a
     * released fork client's: v13, stack_sizes present, quest_shares/timers absent. Does NOT claim
     * to reproduce an old fork build's actual historical seed rows byte-for-byte - see
     * {@link #forkLineage}. Historical stale-seed-value behavior (e.g. Lynx Claws seeded
     * unstackable) is covered separately, explicitly, by
     * {@link #reconciliationFixesStaleGeneratedFactsFromAnOlderClient} and
     * {@link #reconciliationRespectsProvenanceAndTombstones}.
     */
    @Test
    void lineageA_releasedForkSchemaShapeGainsQuestSharesAndTimers(@TempDir Path dir) throws Exception {
        try (Connection conn = forkLineage(dir, "a.db")) {
            assertEquals(13, version(conn));
            assertTrue(tableExists(conn, "stack_sizes"));
            assertFalse(tableExists(conn, "quest_shares"));
            assertFalse(tableExists(conn, "timers"));
            int seededBefore = count(conn, "SELECT COUNT(*) FROM stack_sizes");
            assertTrue(seededBefore > 100, "expected a populated seed, got " + seededBefore);

            Map<Integer, String> skipped = migrate(conn);

            assertTrue(skipped.isEmpty(), "unexpected skipped migrations: " + skipped);
            assertEquals(15, version(conn));
            // 14 created timers; 15 created the quest_shares upstream's own migration 13 skipped.
            assertTrue(tableExists(conn, "timers"), "timers must exist");
            assertTrue(tableExists(conn, "quest_shares"), "quest_shares must exist");
            assertTrue(tableExists(conn, "stack_sizes"), "stack_sizes must be preserved");
            assertTrue(count(conn, "SELECT COUNT(*) FROM stack_sizes") >= seededBefore,
                "reconciliation must not delete seed rows");
        }
    }

    // ---------------- B: released upstream lineage ----------------

    @Test
    void lineageB_upstreamDatabaseGainsStackSizes(@TempDir Path dir) throws Exception {
        try (Connection conn = upstreamLineage(dir, "b.db")) {
            assertEquals(14, version(conn));
            assertTrue(tableExists(conn, "quest_shares"));
            assertTrue(tableExists(conn, "timers"));
            assertFalse(tableExists(conn, "stack_sizes"));

            Map<Integer, String> skipped = migrate(conn);

            assertTrue(skipped.isEmpty(), "unexpected skipped migrations: " + skipped);
            assertEquals(15, version(conn));
            assertTrue(tableExists(conn, "stack_sizes"), "stack_sizes must be created");
            assertTrue(count(conn, "SELECT COUNT(*) FROM stack_sizes") > 100, "stack_sizes must be seeded");
            // Upstream's tables must be left alone.
            assertTrue(tableExists(conn, "quest_shares"));
            assertTrue(tableExists(conn, "timers"));
        }
    }

    // ---------------- C: fresh database ----------------

    @Test
    void lineageC_freshDatabaseCreatesEverythingExactlyOnce(@TempDir Path dir) throws Exception {
        try (Connection conn = open(dir, "c.db")) {
            Map<Integer, String> skipped = migrate(conn);

            assertTrue(skipped.isEmpty(), "unexpected skipped migrations: " + skipped);
            assertEquals(15, version(conn));
            assertTrue(tableExists(conn, "quest_shares"));
            assertTrue(tableExists(conn, "timers"));
            assertTrue(tableExists(conn, "stack_sizes"));
            // One row per applied version - a structure created twice would show as a duplicate
            // version row or a failed re-create.
            assertEquals(15, count(conn, "SELECT COUNT(DISTINCT version) FROM schema_version"));
            assertEquals(count(conn, "SELECT COUNT(*) FROM stack_sizes"),
                count(conn, "SELECT COUNT(DISTINCT name) FROM stack_sizes"),
                "seeding must not duplicate names");
        }
    }

    // ---------------- D: already reconciled ----------------

    @Test
    void lineageD_rerunIsIdempotent(@TempDir Path dir) throws Exception {
        try (Connection conn = open(dir, "d.db")) {
            migrate(conn);
            int rows = count(conn, "SELECT COUNT(*) FROM stack_sizes");
            int versionRows = count(conn, "SELECT COUNT(*) FROM schema_version");

            Map<Integer, String> skipped = migrate(conn);

            assertTrue(skipped.isEmpty());
            assertEquals(15, version(conn));
            assertEquals(rows, count(conn, "SELECT COUNT(*) FROM stack_sizes"));
            assertEquals(versionRows, count(conn, "SELECT COUNT(*) FROM schema_version"));
        }
    }

    // ---------------- E: failure / retry ----------------

    /**
     * The safety property this whole bridge rests on. Migration 15 is optional, so a SQL failure
     * inside it must NOT record version 15 - recording it would permanently bury whichever
     * structure never got created. It must also be atomic: anything it created before the failure
     * has to roll back, so the next start retries from a consistent state.
     *
     * <p>Failure is injected without touching production code, by renaming a column
     * {@code reconcileStackSizes}'s first SELECT depends on. That makes migration 15 fail
     * <em>after</em> it has already created quest_shares in the same transaction, which is exactly
     * the case where swallowing the exception per step (the design rejected in review) would have
     * stamped 15 onto a database that still had no quest_shares.
     */
    @Test
    void lineageE_failureDoesNotRecordVersionAndRetriesCleanly(@TempDir Path dir) throws Exception {
        try (Connection conn = forkLineage(dir, "e.db")) {
            assertEquals(13, version(conn));
            int seedRows = count(conn, "SELECT COUNT(*) FROM stack_sizes");

            exec(conn, "ALTER TABLE stack_sizes RENAME COLUMN max_stack TO max_stack_broken");

            Map<Integer, String> skipped = migrate(conn);

            // Reported, not thrown: migration 15 is optional, so the client still initialises.
            assertTrue(skipped.containsKey(MigrationManager.MIGRATION_STACK_SIZES),
                "migration 15 should be reported skipped, got " + skipped);
            // Version 15 must NOT be recorded.
            assertEquals(14, version(conn), "a failed optional migration must not record its version");
            // Migration 14 committed separately before 15 ran, so timers survives...
            assertTrue(tableExists(conn, "timers"), "timers was committed by migration 14");
            // ...but everything migration 15 itself did must have rolled back.
            assertFalse(tableExists(conn, "quest_shares"),
                "quest_shares was created inside the failed migration and must be rolled back");
            // Existing data is untouched.
            assertTrue(tableExists(conn, "stack_sizes"));
            assertEquals(seedRows, count(conn, "SELECT COUNT(*) FROM stack_sizes"));

            // Correct the failure condition; the next start must retry and succeed.
            exec(conn, "ALTER TABLE stack_sizes RENAME COLUMN max_stack_broken TO max_stack");

            Map<Integer, String> retry = migrate(conn);

            assertTrue(retry.isEmpty(), "retry should not skip anything: " + retry);
            assertEquals(15, version(conn));
            assertTrue(tableExists(conn, "quest_shares"));
            assertTrue(tableExists(conn, "timers"));
            assertTrue(tableExists(conn, "stack_sizes"));
        }
    }

    // ---------------- seed reconciliation semantics ----------------

    /**
     * Migration 15 corrects generated ('seed') rows against the current static table, and must
     * never overwrite what a player calibrated ('manual') or a client actually observed
     * ('learned'), nor resurrect a row a player deleted (tombstoned).
     */
    @Test
    void reconciliationRespectsProvenanceAndTombstones(@TempDir Path dir) throws Exception {
        try (Connection conn = forkLineage(dir, "r.db")) {
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

            migrate(conn);
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
     * table, so the corrections upstream shipped in this sync range are present from the start.
     */
    @Test
    void upstreamLineageSeedCarriesCurrentStaticFacts(@TempDir Path dir) throws Exception {
        try (Connection conn = upstreamLineage(dir, "s.db")) {
            migrate(conn);
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
        try (Connection conn = forkLineage(dir, "o.db")) {
            // What an older fork client's seed produced: Lynx Claws was a catExceptions entry.
            exec(conn, "UPDATE stack_sizes SET max_stack = 1, stackable = 0, provenance = 'seed' "
                + "WHERE name = 'Lynx Claws'");
            // And "Brain" took its category's size rather than the corrected custom size.
            exec(conn, "UPDATE stack_sizes SET max_stack = 4, stackable = 1, provenance = 'seed' "
                + "WHERE name = 'Brain'");

            migrate(conn);

            assertEquals(4, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Lynx Claws'"));
            assertEquals(1, count(conn, "SELECT stackable FROM stack_sizes WHERE name = 'Lynx Claws'"));
            assertEquals(2, count(conn, "SELECT max_stack FROM stack_sizes WHERE name = 'Brain'"));
        }
    }

    /** CLIENT_MAX_SCHEMA_VERSION must match the highest migration, or a DB this client wrote looks too new. */
    @Test
    void clientMaxSchemaVersionMatchesHighestMigration() {
        assertEquals(15, MigrationManager.CLIENT_MAX_SCHEMA_VERSION);
        assertEquals(13, MigrationManager.MIGRATION_QUEST_SHARES);
        assertEquals(14, MigrationManager.MIGRATION_TIMERS);
        assertEquals(15, MigrationManager.MIGRATION_STACK_SIZES);
    }
}

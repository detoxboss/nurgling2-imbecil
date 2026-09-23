package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;
import nurgling.db.PostgresAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for {@code stack_sizes} — the shared, self-correcting override table for
 * {@code nurgling.tools.StackSupporter}'s static stack-size heuristics.
 *
 * <p>Like {@code fish_locations}, there is no field-group merge logic here: a row is either
 * overwritten wholesale by a manual edit, conditionally overwritten by a bigger observed value
 * ({@link #upsertIfBigger}), or tombstoned. See {@code docs/inventory-grid-system.md} for the
 * feature this backs.
 */
public class StackSizeDao {

    /** One row of {@code stack_sizes}. */
    public static final class StackSizeRow {
        public final String profile;
        public final String name;
        public final int maxStack;
        public final boolean stackable;
        public final String provenance;
        public final int version;
        public final Timestamp deletedAt;

        public StackSizeRow(String profile, String name, int maxStack, boolean stackable,
                             String provenance, int version, Timestamp deletedAt) {
            this.profile = profile;
            this.name = name;
            this.maxStack = maxStack;
            this.stackable = stackable;
            this.provenance = provenance;
            this.version = version;
            this.deletedAt = deletedAt;
        }

        public boolean isTombstone() { return deletedAt != null; }
    }

    /** Version and liveness of one row, for the delta poll. */
    public static final class VersionInfo {
        public final int version;
        public final boolean tombstoned;
        public VersionInfo(int version, boolean tombstoned) {
            this.version = version;
            this.tombstoned = tombstoned;
        }
    }

    private static final String COLS =
        "profile, name, max_stack, stackable, provenance, version, deleted_at";

    private static StackSizeRow readRow(ResultSet rs) throws SQLException {
        return new StackSizeRow(
            rs.getString("profile"),
            rs.getString("name"),
            rs.getInt("max_stack"),
            rs.getBoolean("stackable"),
            rs.getString("provenance"),
            rs.getInt("version"),
            rs.getTimestamp("deleted_at"));
    }

    /** Every live row for a profile. */
    public List<StackSizeRow> loadAll(DatabaseAdapter adapter, String profile) throws SQLException {
        List<StackSizeRow> out = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM stack_sizes WHERE profile = ? AND deleted_at IS NULL",
                profile)) {
            while (rs.next()) out.add(readRow(rs));
        }
        return out;
    }

    /** name -> version/liveness for every row of a profile, tombstones included. Drives the delta poll. */
    public Map<String, VersionInfo> getAllVersions(DatabaseAdapter adapter, String profile) throws SQLException {
        Map<String, VersionInfo> out = new HashMap<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT name, version, deleted_at FROM stack_sizes WHERE profile = ?", profile)) {
            while (rs.next()) {
                out.put(rs.getString("name"),
                    new VersionInfo(rs.getInt("version"), rs.getTimestamp("deleted_at") != null));
            }
        }
        return out;
    }

    /** Live rows whose name is in the given list. */
    public List<StackSizeRow> loadByNames(DatabaseAdapter adapter, String profile, List<String> names) throws SQLException {
        List<StackSizeRow> out = new ArrayList<>();
        if (names.isEmpty()) return out;
        for (String name : names) {
            try (ResultSet rs = adapter.executeQuery(
                    "SELECT " + COLS + " FROM stack_sizes WHERE profile = ? AND name = ? AND deleted_at IS NULL",
                    profile, name)) {
                if (rs.next()) out.add(readRow(rs));
            }
        }
        return out;
    }

    /**
     * Passive-learning write: insert the row if absent, otherwise overwrite it only when
     * {@code observedMax} is strictly bigger than what's currently recorded (or the row is a
     * tombstone). A real observed stack is proof the recorded value was too low — this always wins
     * regardless of the current row's provenance, including 'manual', by design (no legitimate
     * reason a manual entry would exceed the server's true cap).
     */
    public void upsertIfBigger(DatabaseAdapter adapter, String profile, String name,
                                int observedMax, String touchedBy) throws SQLException {
        if (adapter instanceof PostgresAdapter) {
            adapter.executeUpdate(
                "INSERT INTO stack_sizes (profile, name, max_stack, stackable, provenance, version, " +
                "updated_at, last_touched_by, last_touched_at, deleted_at) " +
                "VALUES (?, ?, ?, TRUE, 'learned', 1, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL) " +
                "ON CONFLICT (profile, name) DO UPDATE SET " +
                "max_stack = EXCLUDED.max_stack, stackable = TRUE, provenance = 'learned', " +
                "version = stack_sizes.version + 1, updated_at = CURRENT_TIMESTAMP, " +
                "last_touched_by = EXCLUDED.last_touched_by, last_touched_at = CURRENT_TIMESTAMP, " +
                "deleted_at = NULL " +
                "WHERE stack_sizes.max_stack < EXCLUDED.max_stack OR stack_sizes.deleted_at IS NOT NULL",
                profile, name, observedMax, touchedBy);
        } else {
            int updated = adapter.executeUpdate(
                "UPDATE stack_sizes SET max_stack = ?, stackable = 1, provenance = 'learned', " +
                "version = version + 1, updated_at = CURRENT_TIMESTAMP, last_touched_by = ?, " +
                "last_touched_at = CURRENT_TIMESTAMP, deleted_at = NULL " +
                "WHERE profile = ? AND name = ? AND (max_stack < ? OR deleted_at IS NOT NULL)",
                observedMax, touchedBy, profile, name, observedMax);
            if (updated == 0) {
                // Either the row is already >= observedMax (nothing to do), or it doesn't exist yet.
                // INSERT OR IGNORE only inserts in the latter case; a real existing-and-already-good
                // row is left untouched rather than reported as a conflict.
                adapter.executeUpdate(
                    "INSERT OR IGNORE INTO stack_sizes " +
                    "(profile, name, max_stack, stackable, provenance, version, updated_at, " +
                    "last_touched_by, last_touched_at, deleted_at) " +
                    "VALUES (?, ?, ?, 1, 'learned', 1, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL)",
                    profile, name, observedMax, touchedBy);
            }
        }
    }

    /** Calibration-UI write: unconditionally set a row's values, provenance forced to 'manual'. */
    public void setManual(DatabaseAdapter adapter, String profile, String name,
                           int maxStack, boolean stackable, String touchedBy) throws SQLException {
        if (adapter instanceof PostgresAdapter) {
            adapter.executeUpdate(
                "INSERT INTO stack_sizes (profile, name, max_stack, stackable, provenance, version, " +
                "updated_at, last_touched_by, last_touched_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, 'manual', 1, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL) " +
                "ON CONFLICT (profile, name) DO UPDATE SET " +
                "max_stack = EXCLUDED.max_stack, stackable = EXCLUDED.stackable, provenance = 'manual', " +
                "version = stack_sizes.version + 1, updated_at = CURRENT_TIMESTAMP, " +
                "last_touched_by = EXCLUDED.last_touched_by, last_touched_at = CURRENT_TIMESTAMP, " +
                "deleted_at = NULL",
                profile, name, maxStack, stackable, touchedBy);
        } else {
            adapter.executeUpdate(
                "INSERT OR REPLACE INTO stack_sizes " +
                "(profile, name, max_stack, stackable, provenance, version, updated_at, " +
                "last_touched_by, last_touched_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, 'manual', " +
                "COALESCE((SELECT version + 1 FROM stack_sizes WHERE profile = ? AND name = ?), 1), " +
                "CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL)",
                profile, name, maxStack, stackable ? 1 : 0, profile, name, touchedBy);
        }
    }

    /** Soft-delete a row: the item falls back to StackSupporter's static table again. */
    public void tombstone(DatabaseAdapter adapter, String profile, String name, String touchedBy) throws SQLException {
        adapter.executeUpdate(
            "UPDATE stack_sizes SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, " +
            "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP " +
            "WHERE profile = ? AND name = ? AND deleted_at IS NULL",
            touchedBy, profile, name);
    }
}

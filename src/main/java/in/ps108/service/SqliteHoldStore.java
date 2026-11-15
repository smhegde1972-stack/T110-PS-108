package in.ps108.service;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** SQLite transaction boundary for durable enquiry, hold, queue, and slot state. */
public final class SqliteHoldStore {
    @FunctionalInterface
    public interface Work<T> { T run(Connection connection) throws Exception; }

    private final String url;

    public SqliteHoldStore(Path path) {
        Objects.requireNonNull(path, "Database path is required");
        Path absolute=path.toAbsolutePath().normalize();
        try { if(absolute.getParent()!=null)Files.createDirectories(absolute.getParent()); }
        catch(IOException e) { throw new IllegalStateException("Could not create SQLite database directory",e); }
        this.url = "jdbc:sqlite:" + absolute;
        initialize();
    }

    private Connection connect() throws SQLException {
        Connection connection=DriverManager.getConnection(url);
        try (Statement statement=connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=10000");
        }
        return connection;
    }

    private void initialize() {
        try (Connection connection=connect(); Statement statement=connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE IF NOT EXISTS enquiries ("+
                    "id TEXT PRIMARY KEY, client TEXT NOT NULL, venue_id TEXT NOT NULL, event_date TEXT NOT NULL, slot TEXT NOT NULL, guests INTEGER NOT NULL, created_at TEXT NOT NULL, status TEXT NOT NULL, queue_position INTEGER)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_waiting_queue_position ON enquiries(venue_id,event_date,slot,queue_position) WHERE status='QUEUED'");
            statement.execute("CREATE TABLE IF NOT EXISTS enquiry_packages (enquiry_id TEXT NOT NULL REFERENCES enquiries(id) ON DELETE CASCADE, vendor_id TEXT NOT NULL, package_name TEXT NOT NULL, PRIMARY KEY(enquiry_id,vendor_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS bookings ("+
                    "id TEXT PRIMARY KEY, client TEXT NOT NULL, venue_id TEXT NOT NULL, event_date TEXT NOT NULL, slot TEXT NOT NULL, guests INTEGER NOT NULL, status TEXT NOT NULL, " +
                    "actual_guests INTEGER NOT NULL DEFAULT 0, actuals_recorded INTEGER NOT NULL DEFAULT 0, advances_paid TEXT NOT NULL DEFAULT '0.00', damages TEXT NOT NULL DEFAULT '0.00', switch_fees TEXT NOT NULL DEFAULT '0.00', " +
                    "date_change_credit TEXT NOT NULL DEFAULT '0.00', date_change_credit_redeemed TEXT NOT NULL DEFAULT '0.00', date_change_credit_applied TEXT NOT NULL DEFAULT '0.00', free_date_change_used INTEGER NOT NULL DEFAULT 0, " +
                    "hold_created_at TEXT, hold_expires_at TEXT, enquiry_id TEXT)");
            statement.execute("CREATE TABLE IF NOT EXISTS booking_packages (booking_id TEXT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE, vendor_id TEXT NOT NULL, package_name TEXT NOT NULL, PRIMARY KEY(booking_id,vendor_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS active_hold_slots (venue_id TEXT NOT NULL, event_date TEXT NOT NULL, slot TEXT NOT NULL, enquiry_id TEXT NOT NULL REFERENCES enquiries(id) ON DELETE CASCADE, PRIMARY KEY(venue_id,event_date,slot))");
            statement.execute("CREATE TABLE IF NOT EXISTS confirmed_slots (venue_id TEXT NOT NULL, event_date TEXT NOT NULL, slot TEXT NOT NULL, booking_id TEXT NOT NULL, PRIMARY KEY(venue_id,event_date,slot))");
            statement.execute("CREATE TABLE IF NOT EXISTS store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT OR IGNORE INTO store_meta(key,value) VALUES('schema_version','1')");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialize SQLite hold database", e);
        }
    }

    public <T> T transaction(Work<T> work) {
        try (Connection connection=connect(); Statement transaction=connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                T result=work.run(connection);
                transaction.execute("COMMIT");
                return result;
            } catch (Exception e) {
                try { transaction.execute("ROLLBACK"); } catch (SQLException ignored) { }
                if(e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Hold database transaction failed",e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Hold database transaction failed",e);
        }
    }
}

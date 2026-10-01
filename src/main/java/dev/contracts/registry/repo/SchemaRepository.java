package dev.contracts.registry.repo;

import dev.contracts.registry.domain.SchemaRef;
import dev.contracts.registry.domain.StoredSchema;
import dev.contracts.registry.service.ConflictException;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed, transactional schema store. All publishing happens inside a single
 * {@code BEGIN IMMEDIATE} transaction, so concurrent batches are serialized and a
 * stale {@code expectedLatestVersion} is reported as a 409 conflict.
 */
public class SchemaRepository implements AutoCloseable {

    private final Connection connection;

    public SchemaRepository(String jdbcUrl) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=10000");
                st.execute("PRAGMA foreign_keys=ON");
            }
            migrate();
        } catch (SQLException | ClassNotFoundException e) {
            throw new IllegalStateException("cannot open schema repository: " + e.getMessage(), e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS subjects (
                  subject TEXT PRIMARY KEY,
                  latest_version INTEGER NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS schemas (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  subject TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  schema_json TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  UNIQUE(subject, version)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS schema_references (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  subject TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  ref_subject TEXT NOT NULL,
                  ref_version INTEGER NOT NULL,
                  UNIQUE(subject, version, ref_subject, ref_version)
                )""");
        }
    }

    public interface TransactionalWork<T> {
        T run(Connection tx) throws SQLException;
    }

    /** Runs work in a single immediate transaction; busy lock acquisition is reported as 409. */
    public <T> T inTransaction(TransactionalWork<T> work) {
        try {
            try (Statement st = connection.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            }
            T result;
            try {
                result = work.run(connection);
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            }
            return result;
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("SQLITE_BUSY") || msg.contains("database is locked")) {
                throw new ConflictException("another publish is in progress, retry (database lock)");
            }
            throw new IllegalStateException("repository failure: " + msg, e);
        }
    }

    public Integer latestVersion(Connection tx, String subject) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT latest_version FROM subjects WHERE subject = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    public StoredSchema load(Connection tx, String subject, int version) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT schema_json FROM schemas WHERE subject = ? AND version = ?")) {
            ps.setString(1, subject);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String json = rs.getString(1);
                return new StoredSchema(subject, version, json, loadRefs(tx, subject, version));
            }
        }
    }

    public List<SchemaRef> loadRefs(Connection tx, String subject, int version) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT ref_subject, ref_version FROM schema_references WHERE subject = ? AND version = ? ORDER BY ref_subject, ref_version")) {
            ps.setString(1, subject);
            ps.setInt(2, version);
            List<SchemaRef> refs = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) refs.add(new SchemaRef(rs.getString(1), rs.getInt(2)));
            }
            return refs;
        }
    }

    public List<Integer> versions(Connection tx, String subject) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT version FROM schemas WHERE subject = ? ORDER BY version")) {
            ps.setString(1, subject);
            List<Integer> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
            return out;
        }
    }

    public Map<String, Integer> subjects(Connection tx) throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Statement st = tx.createStatement();
             ResultSet rs = st.executeQuery("SELECT subject, latest_version FROM subjects ORDER BY subject")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }

    /** Inserts the new version and advances the subject tip; returns the assigned version. */
    public int insert(Connection tx, String subject, int expectedLatest, String schemaJson,
                      List<SchemaRef> references) throws SQLException {
        Integer current = latestVersion(tx, subject);
        if (current == null) {
            if (expectedLatest != 0)
                throw new ConflictException("subject " + subject + " does not exist but expectedLatestVersion=" + expectedLatest);
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO subjects(subject, latest_version) VALUES(?, ?)")) {
                ps.setString(1, subject);
                ps.setInt(2, 1);
                ps.executeUpdate();
            }
        } else {
            if (current != expectedLatest)
                throw new ConflictException("subject " + subject + " expected latest " + expectedLatest
                        + " but stored latest is " + current);
            try (PreparedStatement ps = tx.prepareStatement("UPDATE subjects SET latest_version = ? WHERE subject = ?")) {
                ps.setInt(1, current + 1);
                ps.setString(2, subject);
                ps.executeUpdate();
            }
        }
        int assigned = (current == null ? 0 : current) + 1;
        try (PreparedStatement ps = tx.prepareStatement(
                "INSERT INTO schemas(subject, version, schema_json, created_at) VALUES(?, ?, ?, ?)")) {
            ps.setString(1, subject);
            ps.setInt(2, assigned);
            ps.setString(3, schemaJson);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
        for (SchemaRef ref : references) {
            try (PreparedStatement ps = tx.prepareStatement(
                    "INSERT INTO schema_references(subject, version, ref_subject, ref_version) VALUES(?, ?, ?, ?)")) {
                ps.setString(1, subject);
                ps.setInt(2, assigned);
                ps.setString(3, ref.subject());
                ps.setInt(4, ref.version());
                ps.executeUpdate();
            }
        }
        return assigned;
    }

    @Override public void close() {
        try { connection.close(); } catch (SQLException ignored) {}
    }
}

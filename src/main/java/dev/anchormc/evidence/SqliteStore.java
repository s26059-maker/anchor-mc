package dev.anchormc.evidence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** SQLite 파일 저장소. 연결 하나를 동기화해서 쓴다(쓰기량이 적다). */
public final class SqliteStore implements EvidenceStore {
    private final Connection conn;

    public SqliteStore(Path file) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("CREATE TABLE IF NOT EXISTS accounts ("
                        + "uuid TEXT PRIMARY KEY, name TEXT NOT NULL,"
                        + "decoy_n INTEGER NOT NULL, decoy_hits INTEGER NOT NULL,"
                        + "placebo_n INTEGER NOT NULL, placebo_hits INTEGER NOT NULL,"
                        + "logs TEXT NOT NULL, confirmed_at INTEGER NOT NULL)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name COLLATE NOCASE)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite를 열 수 없다: " + file, e);
        }
    }

    @Override
    public synchronized AccountRecord load(UUID id) {
        return queryOne("SELECT * FROM accounts WHERE uuid = ?", id.toString());
    }

    @Override
    public synchronized AccountRecord findByName(String name) {
        return queryOne("SELECT * FROM accounts WHERE name = ? COLLATE NOCASE ORDER BY rowid DESC LIMIT 1", name);
    }

    private AccountRecord queryOne(String sql, String arg) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                AccountRecord r = new AccountRecord(UUID.fromString(rs.getString("uuid")), rs.getString("name"));
                r.decoyN = rs.getInt("decoy_n");
                r.decoyHits = rs.getInt("decoy_hits");
                r.placeboN = rs.getInt("placebo_n");
                r.placeboHits = rs.getInt("placebo_hits");
                String[] parts = rs.getString("logs").split(",");
                if (parts.length != Mixture.SIZE) {
                    throw new IllegalStateException("저장된 e-value 격자 크기가 다르다: " + parts.length);
                }
                for (int i = 0; i < parts.length; i++) {
                    r.logs[i] = Double.parseDouble(parts[i]);
                }
                r.confirmedAt = rs.getLong("confirmed_at");
                return r;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void save(AccountRecord r) {
        StringBuilder logs = new StringBuilder();
        for (int i = 0; i < r.logs.length; i++) {
            if (i > 0) {
                logs.append(',');
            }
            logs.append(r.logs[i]);
        }
        String sql = "INSERT INTO accounts(uuid,name,decoy_n,decoy_hits,placebo_n,placebo_hits,logs,confirmed_at)"
                + " VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,"
                + " decoy_n=excluded.decoy_n, decoy_hits=excluded.decoy_hits, placebo_n=excluded.placebo_n,"
                + " placebo_hits=excluded.placebo_hits, logs=excluded.logs, confirmed_at=excluded.confirmed_at";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.id.toString());
            ps.setString(2, r.name);
            ps.setInt(3, r.decoyN);
            ps.setInt(4, r.decoyHits);
            ps.setInt(5, r.placeboN);
            ps.setInt(6, r.placeboHits);
            ps.setString(7, logs.toString());
            ps.setLong(8, r.confirmedAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized long[] placeboTotals() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(placebo_n),0), COALESCE(SUM(placebo_hits),0) FROM accounts")) {
            rs.next();
            return new long[] {rs.getLong(1), rs.getLong(2)};
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized int confirmedCount() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts WHERE confirmed_at <> 0")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}

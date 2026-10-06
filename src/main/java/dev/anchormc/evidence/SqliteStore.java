package dev.anchormc.evidence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
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
                migrate(st);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite를 열 수 없다: " + file, e);
        }
    }

    /** 1.1단계에서 늘린 쌍 정확 검정 열을 옛 파일에 덧붙인다. */
    private static void migrate(Statement st) throws SQLException {
        Set<String> cols = new HashSet<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(accounts)")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        for (String c : new String[] {"pair_decoy_only", "pair_placebo_only", "pair_both", "pair_neither"}) {
            if (!cols.contains(c)) {
                st.execute("ALTER TABLE accounts ADD COLUMN " + c + " INTEGER NOT NULL DEFAULT 0");
            }
        }
        for (String c : new String[] {"first_decoy", "first_placebo"}) {
            if (!cols.contains(c)) {
                st.execute("ALTER TABLE accounts ADD COLUMN " + c + " INTEGER NOT NULL DEFAULT 0");
            }
        }
        if (!cols.contains("ever_flags")) {
            st.execute("ALTER TABLE accounts ADD COLUMN ever_flags INTEGER NOT NULL DEFAULT 0");
        }
        if (!cols.contains("logs_first")) {
            st.execute("ALTER TABLE accounts ADD COLUMN logs_first TEXT NOT NULL DEFAULT ''");
        }
        if (!cols.contains("logs_paired")) {
            st.execute("ALTER TABLE accounts ADD COLUMN logs_paired TEXT NOT NULL DEFAULT ''");
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

    private static void parseInto(String csv, double[] out, String what) {
        if (csv.isEmpty()) {
            return;
        }
        String[] parts = csv.split(",");
        if (parts.length != out.length) {
            throw new IllegalStateException("저장된 " + what + " 격자 크기가 다르다: " + parts.length);
        }
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i]);
        }
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
                parseInto(rs.getString("logs"), r.logs, "e-value");
                r.pairDecoyOnly = rs.getInt("pair_decoy_only");
                r.pairPlaceboOnly = rs.getInt("pair_placebo_only");
                r.pairBoth = rs.getInt("pair_both");
                r.pairNeither = rs.getInt("pair_neither");
                parseInto(rs.getString("logs_paired"), r.logsPaired, "쌍 e-value");
                r.ever = rs.getInt("ever_flags");
                r.firstDecoy = rs.getInt("first_decoy");
                r.firstPlacebo = rs.getInt("first_placebo");
                parseInto(rs.getString("logs_first"), r.logsFirst, "먼저 반응 e-value");
                r.confirmedAt = rs.getLong("confirmed_at");
                return r;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String join(double[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.toString();
    }

    @Override
    public synchronized void save(AccountRecord r) {
        String sql = "INSERT INTO accounts(uuid,name,decoy_n,decoy_hits,placebo_n,placebo_hits,logs,confirmed_at,"
                + "pair_decoy_only,pair_placebo_only,pair_both,pair_neither,logs_paired,first_decoy,first_placebo,logs_first,ever_flags)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,"
                + " decoy_n=excluded.decoy_n, decoy_hits=excluded.decoy_hits, placebo_n=excluded.placebo_n,"
                + " placebo_hits=excluded.placebo_hits, logs=excluded.logs, confirmed_at=excluded.confirmed_at,"
                + " pair_decoy_only=excluded.pair_decoy_only, pair_placebo_only=excluded.pair_placebo_only,"
                + " pair_both=excluded.pair_both, pair_neither=excluded.pair_neither, logs_paired=excluded.logs_paired,"
                + " first_decoy=excluded.first_decoy, first_placebo=excluded.first_placebo, logs_first=excluded.logs_first, ever_flags=excluded.ever_flags";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.id.toString());
            ps.setString(2, r.name);
            ps.setInt(3, r.decoyN);
            ps.setInt(4, r.decoyHits);
            ps.setInt(5, r.placeboN);
            ps.setInt(6, r.placeboHits);
            ps.setString(7, join(r.logs));
            ps.setLong(8, r.confirmedAt);
            ps.setInt(9, r.pairDecoyOnly);
            ps.setInt(10, r.pairPlaceboOnly);
            ps.setInt(11, r.pairBoth);
            ps.setInt(12, r.pairNeither);
            ps.setString(13, join(r.logsPaired));
            ps.setInt(14, r.firstDecoy);
            ps.setInt(15, r.firstPlacebo);
            ps.setString(16, join(r.logsFirst));
            ps.setInt(17, r.ever);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void delete(UUID id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM accounts WHERE uuid = ?")) {
            ps.setString(1, id.toString());
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

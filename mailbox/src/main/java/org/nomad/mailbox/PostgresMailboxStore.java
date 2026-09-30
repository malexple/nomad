package org.nomad.mailbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.nomad.core.Envelope;

/**
 * PostgreSQL implementation. append() takes a per-mailbox advisory lock inside the transaction:
 * the sequence value and the commit order therefore agree for one mailbox, and a client that
 * reads "seq > N" cannot miss an envelope that commits late with a lower seq.
 */
public final class PostgresMailboxStore implements MailboxStore {
    private final DataSource ds;

    public PostgresMailboxStore(DataSource ds) {
        this.ds = ds;
    }

    public void initSchema() {
        try (InputStream in = PostgresMailboxStore.class.getResourceAsStream("/nomad/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on classpath");
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                for (String part : sql.split(";")) {
                    if (!part.isBlank()) {
                        st.execute(part);
                    }
                }
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("cannot init schema", e);
        }
    }

    @Override
    public long epoch() {
        try (Connection c = ds.getConnection()) {
            return readEpoch(c);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Call on the new primary right after promoting a replica. */
    public long bumpEpoch() {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "update node_epoch set epoch = epoch + 1 where id = 1 returning epoch");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long readEpoch(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select epoch from node_epoch where id = 1");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    public AppendResult append(Envelope e) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("select pg_advisory_xact_lock(hashtext(?))")) {
                    ps.setString(1, e.mailboxId());
                    ps.execute();
                }
                Long seq = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "insert into envelope(envelope_id, version, mailbox_id, ciphertext, expiry_day) "
                                + "values (?, ?, ?, ?, ?) on conflict (envelope_id) do nothing returning seq")) {
                    ps.setObject(1, e.envelopeId());
                    ps.setInt(2, e.version());
                    ps.setString(3, e.mailboxId());
                    ps.setBytes(4, e.ciphertext());
                    ps.setLong(5, e.expiryDay());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            seq = rs.getLong(1);
                        }
                    }
                }
                boolean duplicate = false;
                if (seq == null) {
                    duplicate = true;
                    try (PreparedStatement ps = c.prepareStatement(
                            "select seq from envelope where envelope_id = ?")) {
                        ps.setObject(1, e.envelopeId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalStateException("duplicate envelope vanished");
                            }
                            seq = rs.getLong(1);
                        }
                    }
                }
                c.commit();
                return new AppendResult(seq, duplicate);
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public ReadResult read(String mailboxId, long afterSeq, int limit) {
        try (Connection c = ds.getConnection()) {
            long epoch = readEpoch(c);
            List<StoredEnvelope> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "select seq, envelope_id, version, ciphertext, expiry_day from envelope "
                            + "where mailbox_id = ? and seq > ? order by seq limit ?")) {
                ps.setString(1, mailboxId);
                ps.setLong(2, afterSeq);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Envelope env = new Envelope(
                                rs.getInt(3), (UUID) rs.getObject(2), mailboxId, rs.getBytes(4), rs.getLong(5));
                        out.add(new StoredEnvelope(rs.getLong(1), env));
                    }
                }
            }
            return new ReadResult(epoch, out);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int deleteUpTo(String mailboxId, long seq) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "delete from envelope where mailbox_id = ? and seq <= ?")) {
            ps.setString(1, mailboxId);
            ps.setLong(2, seq);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int purgeExpired(long todayEpochDay) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("delete from envelope where expiry_day < ?")) {
            ps.setLong(1, todayEpochDay);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

package org.nomad.mailbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.nomad.core.OneTimePrekey;
import org.nomad.core.PrekeyBundle;

/** Tables are created by PostgresMailboxStore.initSchema() (same schema.sql). */
public final class PostgresPrekeyDirectory implements PrekeyDirectory {
    private final DataSource ds;

    public PostgresPrekeyDirectory(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void publish(PrekeyBundle b, List<OneTimePrekey> newOpks) {
        String uid = b.uid();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "insert into identity_bundle(uid, sig_key, ik_dh, sig_ik_dh, spk_id, spk, sig_spk) "
                                + "values (?, ?, ?, ?, ?, ?, ?) on conflict (uid) do update set "
                                + "ik_dh = excluded.ik_dh, sig_ik_dh = excluded.sig_ik_dh, spk_id = excluded.spk_id, "
                                + "spk = excluded.spk, sig_spk = excluded.sig_spk, updated_at = now()")) {
                    ps.setString(1, uid);
                    ps.setBytes(2, b.sigKey());
                    ps.setBytes(3, b.ikDh());
                    ps.setBytes(4, b.sigIkDh());
                    ps.setInt(5, b.spkId());
                    ps.setBytes(6, b.spk());
                    ps.setBytes(7, b.sigSpk());
                    ps.executeUpdate();
                }
                int have = count(c, uid);
                for (OneTimePrekey k : newOpks) {
                    if (have >= MAX_ONE_TIME_PREKEYS) {
                        break;
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "insert into one_time_prekey(uid, opk_id, pub) values (?, ?, ?) "
                                    + "on conflict do nothing")) {
                        ps.setString(1, uid);
                        ps.setInt(2, k.id());
                        ps.setBytes(3, k.pub());
                        have += ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<PrekeyBundle> fetch(String uid) {
        try (Connection c = ds.getConnection()) {
            PrekeyBundle base = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "select sig_key, ik_dh, sig_ik_dh, spk_id, spk, sig_spk from identity_bundle where uid = ?")) {
                ps.setString(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        base = new PrekeyBundle(
                                rs.getBytes(1), rs.getBytes(2), rs.getBytes(3), rs.getInt(4), rs.getBytes(5),
                                rs.getBytes(6), null);
                    }
                }
            }
            if (base == null) {
                return Optional.empty();
            }
            OneTimePrekey opk = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "delete from one_time_prekey where uid = ? and opk_id = ("
                            + "select opk_id from one_time_prekey where uid = ? order by opk_id limit 1 "
                            + "for update skip locked) returning opk_id, pub")) {
                ps.setString(1, uid);
                ps.setString(2, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        opk = new OneTimePrekey(rs.getInt(1), rs.getBytes(2));
                    }
                }
            }
            return Optional.of(base.withOpk(opk));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int oneTimePrekeyCount(String uid) {
        try (Connection c = ds.getConnection()) {
            return count(c, uid);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(Connection c, String uid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from one_time_prekey where uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}

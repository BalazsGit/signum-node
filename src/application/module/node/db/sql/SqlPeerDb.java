package application.module.node.db.sql;

import application.module.node.db.PeerDb;
import application.module.node.schema.tables.records.PeerRecord;
import org.jooq.Insert;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static application.module.node.schema.Tables.PEER;

public class SqlPeerDb implements PeerDb {

    private final DbContext dbContext;

    /** @deprecated Use {@link #SqlPeerDb(DbContext)} instead */
    @Deprecated
    public SqlPeerDb() {
        this.dbContext = null;
    }

    /**
     * Constructor with DbContext injected.
     *
     * @param dbContext the database context instance
     */
    public SqlPeerDb(DbContext dbContext) {
        this.dbContext = dbContext;
    }

    @Override
    public List<String> loadPeers() {
        return dbContext.fetchWithDSLContext(ctx -> {
            return ctx.selectFrom(PEER).fetch(PEER.ADDRESS, String.class);
        });
    }

    @Override
    public void deletePeers(Collection<String> peers) {
        dbContext.useDSLContext(ctx -> {
            for (String peer : peers) {
                ctx.deleteFrom(PEER).where(PEER.ADDRESS.eq(peer)).execute();
            }
        });
    }

    @Override
    public void addPeers(Collection<String> peers) {
        dbContext.useDSLContext(ctx -> {
            List<Insert<PeerRecord>> inserts = peers.stream().map(peer -> ctx.insertInto(PEER).set(PEER.ADDRESS, peer))
                    .collect(Collectors.toList());
            ctx.batch(inserts).execute();
        });
    }

    @Override
    public void optimize() {
        dbContext.optimizeTable(PEER.getName());
    }
}
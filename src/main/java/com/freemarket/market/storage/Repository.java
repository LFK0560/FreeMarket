package com.freemarket.market.storage;

import com.freemarket.market.model.Models.*;
import com.freemarket.market.util.Money;
import java.sql.*;
import java.util.*;

/** 纯 SQL 业务；每个写入口必须由 Database.transaction 调用。 */
public final class Repository {
    private Repository() {}
    @FunctionalInterface public interface Row<T> { T read(ResultSet r) throws SQLException; }
    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) { bind(s, args); return s.executeUpdate(); }
    }
    public static <T> List<T> query(Connection c, String sql, Row<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args); try (ResultSet r = s.executeQuery()) {
                List<T> list = new ArrayList<>(); while (r.next()) list.add(mapper.read(r)); return list;
            }
        }
    }
    private static void bind(PreparedStatement s, Object[] args) throws SQLException {
        for (int i=0;i<args.length;i++) {
            if (args[i] instanceof byte[] b) s.setBytes(i+1,b); else s.setObject(i+1,args[i]);
        }
    }
    private static long count(Connection c, String sql, Object... args) throws SQLException {
        return query(c,sql,r->r.getLong(1),args).getFirst();
    }
    private static <T> T first(List<T> list, String error) {
        if (list.isEmpty()) throw new MarketException(error); return list.getFirst();
    }
    public static void touch(Connection c, String uuid, String name) throws SQLException {
        update(c,"INSERT INTO players(uuid,name) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name",uuid,name);
        update(c,"UPDATE admins SET uuid=? WHERE name=? AND uuid IS NULL",uuid,name);
        update(c,"UPDATE markets SET owner_name=? WHERE owner=? AND status='ACTIVE'",name,uuid);
    }
    public static void usable(Connection c, String owner) throws SQLException {
        if (count(c,"SELECT COUNT(*) FROM operations WHERE owner=? AND status IN ('PREPARED','FROZEN')",owner)>0)
            throw new MarketException("msg.frozen");
    }
    private static void credit(Connection c, String uuid, long cents) throws SQLException {
        if (cents<0 || update(c,"UPDATE players SET balance=balance+? WHERE uuid=? AND balance+reserved_credit<=?",cents,uuid,Money.MAX-cents)!=1)
            throw new MarketException("msg.balance-limit");
    }
    private static void debit(Connection c, String uuid, long cents) throws SQLException {
        if (cents<0 || update(c,"UPDATE players SET balance=balance-? WHERE uuid=? AND balance>=?",cents,uuid,cents)!=1)
            throw new MarketException("msg.insufficient");
    }
    public static Market market(Connection c, String id) throws SQLException {
        return first(query(c,"SELECT * FROM markets WHERE id=? AND status='ACTIVE'",Repository::marketRow,id),"msg.market-gone");
    }
    private static Market marketRow(ResultSet r) throws SQLException {
        return new Market(r.getString("id"),r.getString("owner"),r.getString("owner_name"),r.getInt("tax_bps"),r.getLong("tax_balance"));
    }
    private static MarketChunk chunkRow(ResultSet r) throws SQLException {
        return new MarketChunk(r.getString("market_id"),r.getString("world_id"),r.getString("world_name"),
            r.getString("environment"),r.getInt("x"),r.getInt("z"),r.getInt("y"));
    }
    private static Listing listingRow(ResultSet r) throws SQLException {
        return new Listing(r.getLong("id"),r.getString("seller"),r.getString("seller_name"),r.getString("market_id"),
            r.getString("item_key"),r.getString("category"),r.getString("search"),r.getBytes("item"),r.getInt("original"),
            r.getInt("remaining"),r.getInt("sold"),r.getLong("income"),r.getLong("price"),r.getInt("tax_bps"),r.getLong("created"),r.getLong("expires"),r.getString("status"));
    }
    private static Goods goodsRow(ResultSet r) throws SQLException {
        return new Goods(r.getLong("id"),r.getString("owner"),r.getString("market_id"),r.getBytes("item"),r.getInt("amount"),
            r.getLong("ready_at"),r.getString("reason"),r.getString("status"));
    }
    private static Operation operationRow(ResultSet r) throws SQLException {
        Long goods = r.getObject("goods_id")==null?null:r.getLong("goods_id");
        return new Operation(r.getString("id"),r.getString("owner"),r.getString("owner_name"),r.getString("market_id"),
            r.getString("kind"),r.getString("status"),r.getLong("cents"),r.getString("currency"),goods,
            r.getBytes("inventory_before"),r.getBytes("inventory_after"),r.getLong("created"),r.getString("note"));
    }
    public static Listing listing(Connection c,long id) throws SQLException {
        return first(query(c,"SELECT * FROM listings WHERE id=?",Repository::listingRow,id),"msg.sold");
    }
    public static Goods goods(Connection c,long id) throws SQLException {
        return first(query(c,"SELECT * FROM goods WHERE id=?",Repository::goodsRow,id),"msg.goods-gone");
    }
    public static Snapshot snapshot(Connection c,String owner,long now) throws SQLException {
        long balance = count(c,"SELECT balance FROM players WHERE uuid=?",owner);
        boolean frozen = count(c,"SELECT COUNT(*) FROM operations WHERE owner=? AND status IN ('PREPARED','FROZEN')",owner)>0;
        return new Snapshot(new Account(balance,frozen),
            query(c,"SELECT * FROM markets WHERE status='ACTIVE'",Repository::marketRow),
            query(c,"SELECT * FROM chunks",Repository::chunkRow),
            query(c,"SELECT * FROM listings WHERE status='ACTIVE' AND expires>? ORDER BY created DESC",Repository::listingRow,now),
            query(c,"SELECT * FROM goods WHERE owner=? AND status IN ('AVAILABLE','RESERVED') ORDER BY id DESC",Repository::goodsRow,owner),
            new HashSet<>(query(c,"SELECT item_key FROM favorites WHERE owner=?",r->r.getString(1),owner)));
    }
    public static MarketChunk buyChunk(Connection c,String owner,String name,String worldId,String worldName,
                                      String environment,int x,int z,int y,long fee,int defaultTax,int maxChunks) throws SQLException {
        usable(c,owner);
        if (count(c,"SELECT COUNT(*) FROM chunks WHERE world_id=? AND x=? AND z=?",worldId,x,z)>0)
            throw new MarketException("msg.chunk-taken");
        List<Market> owned=query(c,"SELECT * FROM markets WHERE owner=? AND status='ACTIVE'",Repository::marketRow,owner);
        String id;
        if (owned.isEmpty()) {
            id=UUID.randomUUID().toString();
            update(c,"INSERT INTO markets(id,owner,owner_name,tax_bps) VALUES(?,?,?,?)",id,owner,name,defaultTax);
        } else id=owned.getFirst().id();
        if (maxChunks>0 && count(c,"SELECT COUNT(*) FROM chunks WHERE market_id=?",id)>=maxChunks)
            throw new MarketException("msg.chunk-limit");
        debit(c,owner,fee);
        update(c,"INSERT INTO chunks(market_id,world_id,world_name,environment,x,z,y) VALUES(?,?,?,?,?,?,?)",id,worldId,worldName,environment,x,z,y);
        return new MarketChunk(id,worldId,worldName,environment,x,z,y);
    }
    public static void setTax(Connection c,String owner,String id,int bps) throws SQLException {
        usable(c,owner);
        if (bps<0 || bps>10000) throw new MarketException("msg.number");
        if (update(c,"UPDATE markets SET tax_bps=? WHERE id=? AND owner=? AND status='ACTIVE'",bps,id,owner)!=1)
            throw new MarketException("msg.not-owner");
    }
    public static long claimTax(Connection c,String owner,String id) throws SQLException {
        usable(c,owner); Market m=market(c,id);
        if (!m.owner().equals(owner)) throw new MarketException("msg.not-owner");
        credit(c,owner,m.taxBalance());
        update(c,"UPDATE markets SET tax_balance=0 WHERE id=?",id);
        return m.taxBalance();
    }
    private static void canClose(Connection c,String id) throws SQLException {
        if (count(c,"SELECT COUNT(*) FROM listings WHERE market_id=? AND status='ACTIVE'",id)>0
            || count(c,"SELECT COUNT(*) FROM goods WHERE market_id=? AND status IN ('AVAILABLE','RESERVED')",id)>0
            || count(c,"SELECT COUNT(*) FROM operations WHERE market_id=? AND status IN ('PREPARED','FROZEN')",id)>0)
            throw new MarketException("msg.market-busy");
    }
    public static void closeMarket(Connection c,String owner,String id) throws SQLException {
        usable(c,owner); Market m=market(c,id);
        if (!m.owner().equals(owner)) throw new MarketException("msg.not-owner");
        canClose(c,id); credit(c,owner,m.taxBalance());
        update(c,"DELETE FROM chunks WHERE market_id=?",id);
        update(c,"UPDATE markets SET status='CLOSED',tax_balance=0 WHERE id=? AND status='ACTIVE'",id);
    }
    public static void removeChunk(Connection c,String owner,MarketChunk chunk) throws SQLException {
        usable(c,owner); Market m=market(c,chunk.marketId());
        if (!m.owner().equals(owner)) throw new MarketException("msg.not-owner");
        if (count(c,"SELECT COUNT(*) FROM chunks WHERE market_id=?",m.id())==1) { closeMarket(c,owner,m.id()); return; }
        if (update(c,"DELETE FROM chunks WHERE market_id=? AND world_id=? AND x=? AND z=?",m.id(),chunk.worldId(),chunk.x(),chunk.z())!=1)
            throw new MarketException("msg.market-gone");
    }
    public static Trade buy(Connection c,String buyer,long id,int amount,long expectedPrice,long now,long delay) throws SQLException {
        usable(c,buyer); Listing l=listing(c,id);
        if (!l.status().equals("ACTIVE") || l.expires()<=now || amount<=0 || amount>l.remaining()) throw new MarketException("msg.sold");
        if (l.seller().equals(buyer)) throw new MarketException("msg.self-buy");
        if (l.price()!=expectedPrice) throw new MarketException("msg.changed");
        market(c,l.marketId());
        long total=Money.total(l.price(),amount),tax=Money.tax(total,l.taxBps());
        debit(c,buyer,total);
        int rows=update(c,"UPDATE listings SET remaining=remaining-?,sold=sold+?,income=income+?,status=CASE WHEN remaining=? THEN 'SOLD' ELSE 'ACTIVE' END WHERE id=? AND status='ACTIVE' AND remaining>=? AND price=? AND expires>?",
            amount,amount,total-tax,amount,id,amount,expectedPrice,now);
        if (rows!=1) throw new MarketException("msg.sold");
        credit(c,l.seller(),total-tax);
        if (update(c,"UPDATE markets SET tax_balance=tax_balance+? WHERE id=? AND status='ACTIVE' AND tax_balance<=?",tax,l.marketId(),Money.MAX-tax)!=1)
            throw new MarketException("msg.balance-limit");
        update(c,"INSERT INTO goods(owner,market_id,item,amount,ready_at,reason) VALUES(?,?,?,?,?,'PURCHASE')",buyer,l.marketId(),l.item(),amount,Math.addExact(now,delay));
        update(c,"INSERT INTO trades(listing_id,market_id,item_key,buyer,seller,amount,unit_price,total,tax,time) VALUES(?,?,?,?,?,?,?,?,?,?)",
            id,l.marketId(),l.itemKey(),buyer,l.seller(),amount,l.price(),total,tax,now);
        long tradeId=count(c,"SELECT last_insert_rowid()");
        return new Trade(tradeId,l.itemKey(),buyer,l.seller(),amount,l.price(),total,tax,now);
    }
    public static void reprice(Connection c,String seller,long id,long price,long now) throws SQLException {
        usable(c,seller);
        Listing l=listing(c,id); Money.total(price,l.remaining());
        if (update(c,"UPDATE listings SET price=? WHERE id=? AND seller=? AND status='ACTIVE' AND expires>?",price,id,seller,now)!=1)
            throw new MarketException("msg.sold");
    }
    public static void cancel(Connection c,String seller,long id,long now) throws SQLException {
        usable(c,seller); Listing l=listing(c,id);
        if (!l.seller().equals(seller)) throw new MarketException("msg.not-owner");
        returnListing(c,l,"CANCELLED",now);
    }
    private static void returnListing(Connection c,Listing l,String reason,long now) throws SQLException {
        if (update(c,"UPDATE listings SET status=?,remaining=0 WHERE id=? AND status='ACTIVE'",reason,l.id())!=1)
            throw new MarketException("msg.sold");
        if (l.remaining()>0) update(c,"INSERT INTO goods(owner,market_id,item,amount,ready_at,reason) VALUES(?,?,?,?,?,?)",
            l.seller(),l.marketId(),l.item(),l.remaining(),now,reason);
    }
    public static Map<String,Integer> expire(Connection c,long now,long retainFrom) throws SQLException {
        Map<String,Integer> result=new HashMap<>();
        for (Listing l:query(c,"SELECT * FROM listings WHERE status='ACTIVE' AND expires<=?",Repository::listingRow,now)) {
            returnListing(c,l,"EXPIRED",now); result.merge(l.seller(),1,Integer::sum);
        }
        update(c,"DELETE FROM trades WHERE time<?",retainFrom);
        return result;
    }
    public static void favorite(Connection c,String owner,String key) throws SQLException {
        if (update(c,"DELETE FROM favorites WHERE owner=? AND item_key=?",owner,key)==0)
            update(c,"INSERT INTO favorites(owner,item_key) VALUES(?,?)",owner,key);
    }
    public static void prepare(Connection c,Operation o,List<Draft> drafts,int limit,long min,long max,long maxDuration) throws SQLException {
        usable(c,o.owner());
        if (o.cents()<0 || o.cents()>Money.MAX) throw new MarketException("msg.number");
        switch (o.kind()) {
            case "DEPOSIT" -> {
                if (o.cents()<100 || o.cents()%100!=0) throw new MarketException("msg.number");
                if (update(c,"UPDATE players SET reserved_credit=reserved_credit+? WHERE uuid=? AND balance+reserved_credit<=?",o.cents(),o.owner(),Money.MAX-o.cents())!=1)
                    throw new MarketException("msg.balance-limit");
            }
            case "WITHDRAW" -> {
                if (o.cents()<100 || o.cents()%100!=0) throw new MarketException("msg.number");
                debit(c,o.owner(),o.cents());
                update(c,"UPDATE players SET reserved_credit=reserved_credit+? WHERE uuid=?",o.cents(),o.owner());
            }
            case "SELL" -> {
                Market m=market(c,o.marketId());
                if (drafts.isEmpty() || count(c,"SELECT COUNT(*) FROM listings WHERE seller=? AND status='ACTIVE'",o.owner())+drafts.size()>limit)
                    throw new MarketException("msg.listing-limit");
                for (Draft d:drafts) {
                    if (d.price()<min || (max>0 && d.price()>max) || d.amount()<1 || d.amount()>64 || d.durationMillis()<1 || d.durationMillis()>maxDuration)
                        throw new MarketException("msg.number");
                    Money.total(d.price(),d.amount());
                }
            }
            case "CLAIM" -> {
                Goods g=goods(c,Objects.requireNonNull(o.goodsId()));
                if (!g.owner().equals(o.owner()) || !g.marketId().equals(o.marketId()) || !g.status().equals("AVAILABLE")) throw new MarketException("msg.goods-gone");
                if (g.readyAt()>o.created()) throw new MarketException("msg.delivery-wait");
                market(c,g.marketId());
                if (update(c,"UPDATE goods SET status='RESERVED' WHERE id=? AND owner=? AND status='AVAILABLE'",g.id(),o.owner())!=1)
                    throw new MarketException("msg.goods-gone");
            }
            default -> throw new MarketException("msg.error");
        }
        update(c,"INSERT INTO operations(id,owner,owner_name,market_id,kind,status,cents,currency,goods_id,inventory_before,inventory_after,created) VALUES(?,?,?,?,?,'PREPARED',?,?,?,?,?,?)",
            o.id(),o.owner(),o.ownerName(),o.marketId(),o.kind(),o.cents(),o.currency(),o.goodsId(),o.before(),o.after(),o.created());
        if (o.kind().equals("SELL")) {
            int tax=market(c,o.marketId()).taxBps();
            for (Draft d:drafts) update(c,"INSERT INTO operation_items(operation_id,item,item_key,category,search,amount,price,tax_bps,duration) VALUES(?,?,?,?,?,?,?,?,?)",
                o.id(),d.item(),d.key(),d.category(),d.search(),d.amount(),d.price(),tax,d.durationMillis());
        }
    }
    public static Operation operation(Connection c,String id) throws SQLException {
        return first(query(c,"SELECT * FROM operations WHERE id=?",Repository::operationRow,id),"msg.operation-gone");
    }
    public static void finish(Connection c,String id,long now) throws SQLException {
        Operation o=operation(c,id);
        if (!Set.of("PREPARED","FROZEN").contains(o.status())) throw new MarketException("msg.operation-gone");
        switch (o.kind()) {
            case "DEPOSIT" -> {
                if(update(c,"UPDATE players SET reserved_credit=reserved_credit-? WHERE uuid=? AND reserved_credit>=?",o.cents(),o.owner(),o.cents())!=1)
                    throw new MarketException("msg.operation-gone");
                credit(c,o.owner(),o.cents());
            }
            case "WITHDRAW" -> {
                if(update(c,"UPDATE players SET reserved_credit=reserved_credit-? WHERE uuid=? AND reserved_credit>=?",o.cents(),o.owner(),o.cents())!=1)
                    throw new MarketException("msg.operation-gone");
            }
            case "CLAIM" -> {
                if (update(c,"UPDATE goods SET status='CLAIMED' WHERE id=? AND status='RESERVED'",o.goodsId())!=1)
                    throw new MarketException("msg.goods-gone");
            }
            case "SELL" -> {
                market(c,o.marketId());
                update(c,"INSERT INTO listings(seller,seller_name,market_id,item_key,category,search,item,original,remaining,price,tax_bps,created,expires) SELECT ?,?,?,item_key,category,search,item,amount,amount,price,tax_bps,?,?+duration FROM operation_items WHERE operation_id=?",
                    o.owner(),o.ownerName(),o.marketId(),now,now,id);
            }
            default -> throw new MarketException("msg.error");
        }
        update(c,"UPDATE operations SET status='DONE' WHERE id=?",id);
    }
    public static void abort(Connection c,String id) throws SQLException {
        List<Operation> list=query(c,"SELECT * FROM operations WHERE id=?",Repository::operationRow,id);
        if (list.isEmpty()) return;
        Operation o=list.getFirst();
        if (!Set.of("PREPARED","FROZEN").contains(o.status())) throw new MarketException("msg.operation-gone");
        if (o.kind().equals("WITHDRAW")||o.kind().equals("DEPOSIT"))
            update(c,"UPDATE players SET reserved_credit=reserved_credit-? WHERE uuid=? AND reserved_credit>=?",o.cents(),o.owner(),o.cents());
        if (o.kind().equals("WITHDRAW")) credit(c,o.owner(),o.cents());
        if (o.kind().equals("CLAIM")) update(c,"UPDATE goods SET status='AVAILABLE' WHERE id=? AND status='RESERVED'",o.goodsId());
        update(c,"UPDATE operations SET status='ABORTED' WHERE id=?",id);
    }
    public static void freeze(Connection c,String id,String note) throws SQLException {
        update(c,"UPDATE operations SET status='FROZEN',note=? WHERE id=? AND status='PREPARED'",note,id);
    }
    public static List<Operation> frozen(Connection c) throws SQLException {
        return query(c,"SELECT o.*,p.name AS current_name FROM operations o JOIN players p ON p.uuid=o.owner WHERE o.status IN ('FROZEN','PREPARED') ORDER BY o.created",Repository::operationRow);
    }
    public static Map<String,String> admins(Connection c) throws SQLException {
        Map<String,String> result=new LinkedHashMap<>();
        for (String[] a:query(c,"SELECT name,uuid FROM admins",r->new String[]{r.getString(1),r.getString(2)})) result.put(a[0],a[1]);
        return result;
    }
    public static void setAdmin(Connection c,String name,boolean add) throws SQLException {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new MarketException("msg.player-name");
        if (!add) { update(c,"DELETE FROM admins WHERE name=?",name); return; }
        List<String> uuid=query(c,"SELECT uuid FROM players WHERE name=?",r->r.getString(1),name);
        update(c,"INSERT INTO admins(name,uuid) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET uuid=excluded.uuid",name,uuid.isEmpty()?null:uuid.getFirst());
    }
    public static void resolve(Connection c,String admin,String id,boolean physicalDone,long now) throws SQLException {
        if (count(c,"SELECT COUNT(*) FROM admins WHERE uuid=?",admin)==0) throw new MarketException("msg.not-admin");
        Operation o=operation(c,id);
        if (!o.status().equals("FROZEN")) throw new MarketException("msg.operation-gone");
        if (physicalDone) finish(c,id,now); else abort(c,id);
        update(c,"INSERT INTO audit(actor,operation_id,decision,time) VALUES(?,?,?,?)",admin,id,physicalDone?"CONFIRM_PHYSICAL":"CANCEL_UNAPPLIED",now);
    }
    public static Map<String,PriceStats> prices(Connection c,long from) throws SQLException {
        Map<String,PriceStats> result=new HashMap<>();
        for (Object[] a:query(c,"SELECT item_key,SUM(total*1.0)/SUM(amount),MIN(unit_price),MAX(unit_price),COUNT(*) FROM trades WHERE time>=? GROUP BY item_key",
                r->new Object[]{r.getString(1),new PriceStats(r.getDouble(2)/100.0,r.getDouble(3)/100.0,r.getDouble(4)/100.0,r.getInt(5))},from))
            result.put((String)a[0],(PriceStats)a[1]);
        return result;
    }
    public static History history(Connection c,String key,long from,long bucket) throws SQLException {
        List<PricePoint> points=query(c,"SELECT (time/?) AS bucket,SUM(total*1.0)/SUM(amount),SUM(amount) FROM trades WHERE item_key=? AND time>=? GROUP BY bucket ORDER BY bucket",
            r->new PricePoint(r.getLong(1)*bucket,r.getDouble(2)/100.0,r.getLong(3)),bucket,key,from);
        List<Trade> trades=query(c,"SELECT * FROM trades WHERE item_key=? AND time>=? ORDER BY time DESC,id DESC LIMIT 10",
            r->new Trade(r.getLong("id"),r.getString("item_key"),r.getString("buyer"),r.getString("seller"),r.getInt("amount"),
                r.getLong("unit_price"),r.getLong("total"),r.getLong("tax"),r.getLong("time")),key,from);
        List<PriceStats> stats=query(c,"SELECT SUM(total*1.0)/SUM(amount),MIN(unit_price),MAX(unit_price),COUNT(*) FROM trades WHERE item_key=? AND time>=?",
            r->new PriceStats(r.getDouble(1)/100.0,r.getDouble(2)/100.0,r.getDouble(3)/100.0,r.getInt(4)),key,from);
        return new History(points,trades,stats.getFirst());
    }
    public static Stats stats(Connection c,long from) throws SQLException {
        long[] sums=query(c,"SELECT COALESCE(SUM(total),0),COALESCE(SUM(tax),0),COALESCE(SUM(amount),0) FROM trades WHERE time>=?",
            r->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)},from).getFirst();
        return new Stats(sums[0],sums[1],sums[2],count(c,"SELECT COUNT(*) FROM listings WHERE status='ACTIVE'"),
            query(c,"SELECT item_key FROM trades WHERE time>=? GROUP BY item_key ORDER BY SUM(amount) DESC LIMIT 5",r->r.getString(1),from));
    }
}

package com.freemarket.market;

import com.freemarket.market.model.Models.*;
import com.freemarket.market.storage.*;
import com.freemarket.market.util.Money;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 SQLite 验证账务、竞争购买、领取预留及异常恢复。 */
class SettlementTest {
    @TempDir Path folder;
    private Database db;
    private Path file;
    private final String owner=UUID.randomUUID().toString(),seller=UUID.randomUUID().toString(),
        buyer=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
    private final long now=1_800_000_000_000L;
    private String market;
    @BeforeEach void setup() throws Exception {
        file=folder.resolve("market.db"); db=new Database(file); db.ready().get(10,TimeUnit.SECONDS);
        write(c->{
            for(String id:List.of(owner,seller,buyer,other)) { Repository.touch(c,id,id.equals(owner)?"Owner":id); Repository.update(c,"UPDATE players SET balance=100000 WHERE uuid=?",id); }
            market=Repository.buyChunk(c,owner,"Owner","world","世界","NORMAL",0,0,64,6400,500,0).marketId();
            return null;
        });
    }
    @AfterEach void close() { db.close(); }
    private <T> T write(Database.Work<T> work) throws Exception { return db.transaction(work).get(10,TimeUnit.SECONDS); }
    private <T> T read(Database.Work<T> work) throws Exception { return db.read(work).get(10,TimeUnit.SECONDS); }
    private long scalar(String sql,Object... args) throws Exception { return read(c->Repository.query(c,sql,r->r.getLong(1),args).getFirst()); }
    private long balance(String uuid) throws Exception { return scalar("SELECT balance FROM players WHERE uuid=?",uuid); }
    private Operation operation(String uuid,String kind,long cents,Long goods,long time) {
        return new Operation(UUID.randomUUID().toString(),uuid,uuid,kind.equals("SELL")||kind.equals("CLAIM")?market:null,
            kind,"PREPARED",cents,"DIAMOND",goods,new byte[]{1,2},new byte[]{3,4},time,"");
    }
    private long listing(int amount,long price,String key,long duration) throws Exception {
        Operation o=operation(seller,"SELL",0,null,now);
        write(c->{
            Repository.prepare(c,o,List.of(new Draft(0,new byte[]{42},key,"building","圆石",amount,price,duration)),10,1,0,86400000L);
            Repository.finish(c,o.id(),now); return null;
        });
        return scalar("SELECT MAX(id) FROM listings");
    }
    private Trade buy(String who,long id,int amount,long price,long time) throws Exception {
        return write(c->Repository.buy(c,who,id,amount,price,time,10000));
    }
    @Test void partialPurchaseConservesMoneyAndCreatesDelivery() throws Exception {
        long id=listing(20,125,"stone@plain",86400000);
        Trade trade=buy(buyer,id,5,125,now);
        assertEquals(625,trade.total()); assertEquals(31,trade.tax());
        assertEquals(99375,balance(buyer)); assertEquals(100594,balance(seller));
        assertEquals(31,scalar("SELECT tax_balance FROM markets WHERE id=?",market));
        Listing l=read(c->Repository.listing(c,id)); assertEquals(15,l.remaining());assertEquals(5,l.sold());assertEquals("ACTIVE",l.status());
        Goods g=read(c->Repository.snapshot(c,buyer,now).goods().getFirst());assertEquals(5,g.amount());assertEquals(now+10000,g.readyAt());
        assertEquals(new byte[]{42}[0],g.item()[0]);
        assertEquals(100000+100000+93600,balance(owner)+balance(seller)+balance(buyer)+31);
    }
    @Test void competingBuyersOnlyOneWins() throws Exception {
        long id=listing(7,100,"stone@race",86400000);
        List<CompletableFuture<Trade>> futures=new ArrayList<>();
        for(int i=0;i<40;i++) {
            String who=i%2==0?buyer:other;
            futures.add(db.transaction(c->Repository.buy(c,who,id,7,100,now,10000)));
        }
        int successes=0;
        for(var f:futures) try { f.get(10,TimeUnit.SECONDS);successes++; } catch(ExecutionException expected) { assertInstanceOf(MarketException.class,expected.getCause()); }
        assertEquals(1,successes);assertEquals(1,scalar("SELECT COUNT(*) FROM trades"));assertEquals(1,scalar("SELECT COUNT(*) FROM goods"));
        assertEquals(199300,balance(buyer)+balance(other));assertEquals("SOLD",read(c->Repository.listing(c,id)).status());
    }
    @Test void insufficientBalanceRollsBackEverything() throws Exception {
        long id=listing(1,100,"stone@insufficient",86400000);
        write(c->Repository.update(c,"UPDATE players SET balance=50 WHERE uuid=?",buyer));
        assertThrows(ExecutionException.class,()->buy(buyer,id,1,100,now));
        assertEquals(50,balance(buyer));assertEquals(100000,balance(seller));assertEquals(0,scalar("SELECT COUNT(*) FROM trades"));
        assertEquals("ACTIVE",read(c->Repository.listing(c,id)).status());assertEquals(1,read(c->Repository.listing(c,id)).remaining());
    }
    @Test void taxesAreLockedAtListingAndSupportOneHundredPercent() throws Exception {
        long first=listing(1,100,"stone@tax-old",86400000);
        write(c->{Repository.setTax(c,owner,market,10000);return null;});
        long second=listing(1,100,"stone@tax-new",86400000);
        assertEquals(5,buy(buyer,first,1,100,now).tax());
        assertEquals(100,buy(buyer,second,1,100,now).tax());
        assertEquals(100095,balance(seller));assertEquals(105,scalar("SELECT tax_balance FROM markets WHERE id=?",market));
    }
    @Test void pendingDeliveryRequiresDelayAndCanOnlyBeReservedOnce() throws Exception {
        long id=listing(1,100,"stone@delivery",86400000);buy(buyer,id,1,100,now);
        long goods=scalar("SELECT id FROM goods");
        Operation early=operation(buyer,"CLAIM",0,goods,now+9999);
        assertThrows(ExecutionException.class,()->write(c->{Repository.prepare(c,early,List.of(),10,1,0,86400000);return null;}));
        assertEquals("AVAILABLE",read(c->Repository.goods(c,goods)).status());
        Operation correct=operation(buyer,"CLAIM",0,goods,now+10000);
        write(c->{Repository.prepare(c,correct,List.of(),10,1,0,86400000);return null;});
        Operation duplicate=operation(buyer,"CLAIM",0,goods,now+10000);
        assertThrows(ExecutionException.class,()->write(c->{Repository.prepare(c,duplicate,List.of(),10,1,0,86400000);return null;}));
        write(c->{Repository.finish(c,correct.id(),now+10000);return null;});
        assertEquals("CLAIMED",read(c->Repository.goods(c,goods)).status());
        assertThrows(ExecutionException.class,()->write(c->{Repository.finish(c,correct.id(),now+10000);return null;}));
    }
    @Test void uncleanWithdrawalIsFrozenAndAdministratorCanUndoReservation() throws Exception {
        Operation o=operation(buyer,"WITHDRAW",1000,null,now);
        write(c->{Repository.prepare(c,o,List.of(),10,1,0,86400000);return null;});
        assertEquals(99000,balance(buyer));db.close();db=new Database(file);db.ready().get(10,TimeUnit.SECONDS);
        assertEquals("FROZEN",read(c->Repository.operation(c,o.id())).status());
        assertArrayEquals(new byte[]{1,2},read(c->Repository.operation(c,o.id())).before());
        assertThrows(ExecutionException.class,()->write(c->{Repository.usable(c,buyer);return null;}));
        assertThrows(ExecutionException.class,()->write(c->{Repository.resolve(c,seller,o.id(),false,now);return null;}));
        write(c->{Repository.setAdmin(c,"Owner",true);Repository.resolve(c,owner,o.id(),false,now);return null;});
        assertEquals(100000,balance(buyer));assertEquals("ABORTED",read(c->Repository.operation(c,o.id())).status());
        assertEquals(1,scalar("SELECT COUNT(*) FROM audit"));
        assertThrows(ExecutionException.class,()->write(c->{Repository.resolve(c,owner,o.id(),false,now);return null;}));
        assertEquals(100000,balance(buyer));
    }
    @Test void depositCreditsOnlyAfterAcknowledgementAndOnlyOnce() throws Exception {
        Operation o=operation(buyer,"DEPOSIT",6400,null,now);
        write(c->{Repository.prepare(c,o,List.of(),10,1,0,86400000);return null;});
        assertEquals(100000,balance(buyer));
        write(c->{Repository.freeze(c,o.id(),"核查");Repository.setAdmin(c,"Owner",true);Repository.resolve(c,owner,o.id(),true,now);return null;});
        assertEquals(106400,balance(buyer));
        assertThrows(ExecutionException.class,()->write(c->{Repository.finish(c,o.id(),now);return null;}));
        assertEquals(106400,balance(buyer));
    }
    @Test void oneMarketCanExpandAcrossWorldsAndDistantChunks() throws Exception {
        MarketChunk nether=write(c->Repository.buyChunk(c,owner,"Owner","nether","下界","NETHER",1000,-1000,70,6400,500,0));
        MarketChunk end=write(c->Repository.buyChunk(c,owner,"Owner","end","末地","THE_END",-500,500,60,6400,500,0));
        assertEquals(market,nether.marketId());assertEquals(market,end.marketId());
        assertEquals(1,scalar("SELECT COUNT(*) FROM markets WHERE owner=? AND status='ACTIVE'",owner));
        assertEquals(3,scalar("SELECT COUNT(*) FROM chunks"));assertEquals(80800,balance(owner));
        long before=balance(other);
        assertThrows(ExecutionException.class,()->write(c->Repository.buyChunk(c,other,"Other","nether","下界","NETHER",1000,-1000,70,6400,500,0)));
        assertEquals(before,balance(other));
    }
    @Test void closureChecksUnclaimedGoodsButIgnoresCompletedHistory() throws Exception {
        long id=listing(1,100,"stone@closure",86400000);
        assertThrows(ExecutionException.class,()->write(c->{Repository.closeMarket(c,owner,market);return null;}));
        buy(buyer,id,1,100,now);
        assertThrows(ExecutionException.class,()->write(c->{Repository.closeMarket(c,owner,market);return null;}));
        long goods=scalar("SELECT id FROM goods");Operation claim=operation(buyer,"CLAIM",0,goods,now+10000);
        write(c->{Repository.prepare(c,claim,List.of(),10,1,0,86400000);Repository.finish(c,claim.id(),now+10000);Repository.closeMarket(c,owner,market);return null;});
        assertEquals(0,scalar("SELECT COUNT(*) FROM chunks"));assertEquals(1,scalar("SELECT COUNT(*) FROM trades"));
        assertEquals(93605,balance(owner));assertEquals("CLOSED",read(c->Repository.query(c,"SELECT status FROM markets WHERE id=?",r->r.getString(1),market).getFirst()));
    }
    @Test void expiryReturnsOnlyUnsoldRemainderAndIsIdempotent() throws Exception {
        long id=listing(20,100,"stone@expiry",1000);buy(buyer,id,5,100,now);
        write(c->Repository.expire(c,now+1001,0));
        List<Goods> returned=read(c->Repository.snapshot(c,seller,now+1001).goods());
        assertEquals(1,returned.size());assertEquals(15,returned.getFirst().amount());assertEquals("EXPIRED",returned.getFirst().reason());
        write(c->Repository.expire(c,now+1002,0));assertEquals(1,read(c->Repository.snapshot(c,seller,now+1002).goods()).size());
        assertEquals(0,read(c->Repository.listing(c,id)).remaining());
    }
    @Test void attributeKeysHaveIndependentPriceHistoryAndWeightedAverage() throws Exception {
        long a=listing(3,100,"same-material@plain",86400000),b=listing(1,400,"same-material@enchanted",86400000);
        buy(buyer,a,3,100,now);buy(other,b,1,400,now);
        Map<String,PriceStats> prices=read(c->Repository.prices(c,0));
        assertEquals(1.0,prices.get("same-material@plain").average());
        assertEquals(4.0,prices.get("same-material@enchanted").average());
        long next=listing(1,300,"same-material@plain",86400000);buy(buyer,next,1,300,now+1);
        assertEquals(1.5,read(c->Repository.prices(c,0)).get("same-material@plain").average());
    }
    @Test void frozenSellPreventsClosingAndRejectsExtraPhysicalOperations() throws Exception {
        Operation sell=operation(seller,"SELL",0,null,now);
        write(c->{Repository.prepare(c,sell,List.of(new Draft(0,new byte[]{1},"stone","building","圆石",1,100,1000)),10,1,0,86400000);Repository.freeze(c,sell.id(),"核查");return null;});
        assertThrows(ExecutionException.class,()->write(c->{Repository.closeMarket(c,owner,market);return null;}));
        Operation deposit=operation(seller,"DEPOSIT",100,null,now);
        assertThrows(ExecutionException.class,()->write(c->{Repository.prepare(c,deposit,List.of(),10,1,0,86400000);return null;}));
        assertEquals(0,scalar("SELECT COUNT(*) FROM listings"));
    }
    @Test void fractionalPhysicalWithdrawalsAreRejectedAndVirtualMoneyIsExact() throws Exception {
        Operation o=operation(buyer,"WITHDRAW",150,null,now);
        assertThrows(ExecutionException.class,()->write(c->{Repository.prepare(c,o,List.of(),10,1,0,86400000);return null;}));
        assertEquals(100000,balance(buyer));assertEquals(1,Money.parse("0.01"));assertEquals(100,Money.total(25,4));
        assertEquals(1,Money.tax(10,500));assertThrows(IllegalArgumentException.class,()->Money.parse("NaN"));
        assertThrows(IllegalArgumentException.class,()->Money.parse("0.001"));assertThrows(IllegalArgumentException.class,()->Money.parse("Infinity"));
    }
    @Test void repriceAndCancelCannotAffectAnotherSellersListing() throws Exception {
        long id=listing(2,100,"stone@permissions",86400000);
        assertThrows(ExecutionException.class,()->write(c->{Repository.reprice(c,buyer,id,200,now);return null;}));
        assertThrows(ExecutionException.class,()->write(c->{Repository.cancel(c,buyer,id,now);return null;}));
        assertEquals(100,read(c->Repository.listing(c,id)).price());
        write(c->{Repository.reprice(c,seller,id,200,now);Repository.cancel(c,seller,id,now);return null;});
        assertEquals(1,read(c->Repository.snapshot(c,seller,now).goods()).size());assertEquals("CANCELLED",read(c->Repository.listing(c,id)).status());
    }
    @Test void depositReservationPreventsIncomingIncomeFromOverflowingTheAccount() throws Exception {
        long id=listing(1,100,"stone@reserved-credit",86400000);
        write(c->Repository.update(c,"UPDATE players SET balance=? WHERE uuid=?",Money.MAX-100,seller));
        Operation deposit=operation(seller,"DEPOSIT",100,null,now);
        write(c->{Repository.prepare(c,deposit,List.of(),10,1,0,86400000);return null;});
        assertThrows(ExecutionException.class,()->buy(buyer,id,1,100,now));
        assertEquals("ACTIVE",read(c->Repository.listing(c,id)).status());
        write(c->{Repository.finish(c,deposit.id(),now);return null;});
        assertEquals(Money.MAX,balance(seller));assertEquals(0,scalar("SELECT reserved_credit FROM players WHERE uuid=?",seller));
    }
    @Test void withdrawalRefundSpaceIsReservedUntilTheOperationIsResolved() throws Exception {
        long id=listing(1,100,"stone@withdraw-refund",86400000);
        write(c->Repository.update(c,"UPDATE players SET balance=? WHERE uuid=?",Money.MAX,seller));
        Operation withdrawal=operation(seller,"WITHDRAW",100,null,now);
        write(c->{Repository.prepare(c,withdrawal,List.of(),10,1,0,86400000);return null;});
        assertThrows(ExecutionException.class,()->buy(buyer,id,1,100,now));
        write(c->{Repository.abort(c,withdrawal.id());return null;});
        assertEquals(Money.MAX,balance(seller));assertEquals(0,scalar("SELECT reserved_credit FROM players WHERE uuid=?",seller));
    }
}

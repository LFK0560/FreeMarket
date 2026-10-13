package com.freemarket.market.model;

import java.util.*;

/** 只存值与物品字节，不持有玩家或背包对象，供异步数据库使用。 */
public final class Models {
    private Models() {}
    public record Market(String id, String owner, String ownerName, int taxBps, long taxBalance) {}
    public record MarketChunk(String marketId, String worldId, String worldName, String environment, int x, int z, int y) {}
    public record Listing(long id, String seller, String sellerName, String marketId, String itemKey,
                          String category, String search, byte[] item, int original, int remaining,
                          int sold, long income, long price, int taxBps, long created, long expires, String status) {}
    public record Goods(long id, String owner, String marketId, byte[] item, int amount, long readyAt, String reason, String status) {}
    public record Operation(String id, String owner, String ownerName, String marketId, String kind,
                            String status, long cents, String currency, Long goodsId, byte[] before,
                            byte[] after, long created, String note) {}
    public record Draft(int slot, byte[] item, String key, String category, String search, int amount, long price, long durationMillis) {}
    public record Account(long balance, boolean frozen) {}
    public record PricePoint(long time, double price, long volume) {}
    public record PriceStats(double average, double min, double max, int trades) {}
    public record Trade(long id, String key, String buyer, String seller, int amount, long unitPrice, long total, long tax, long time) {}
    public record Snapshot(Account account, List<Market> markets, List<MarketChunk> chunks,
                           List<Listing> listings, List<Goods> goods, Set<String> favorites) {
        public Market market(String id) { return markets.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null); }
        public Optional<Market> owned(String uuid) { return markets.stream().filter(m -> m.owner().equals(uuid)).findFirst(); }
    }
    public record History(List<PricePoint> points, List<Trade> recent, PriceStats stats) {}
    public record Stats(long total, long tax, long volume, long listings, List<String> hot) {}
}

package com.freemarket.market.market;

import com.freemarket.market.FreeMarket;
import com.freemarket.market.model.Models.*;
import com.freemarket.market.storage.Repository;
import java.util.*;
import java.util.concurrent.*;

/** 主线程维护最多一百二十八项的 LRU，缓存的查询本身仍在数据库线程执行。 */
public final class PriceAnalyzer {
    private record Entry(long expires,CompletableFuture<History> future) {}
    private final FreeMarket plugin;
    private final LinkedHashMap<String,Entry> history=new LinkedHashMap<>(16,0.75f,true);
    private CompletableFuture<Map<String,PriceStats>> all;
    private long allExpires;
    public PriceAnalyzer(FreeMarket plugin) { this.plugin=plugin; }
    public CompletableFuture<Map<String,PriceStats>> all() {
        long now=System.currentTimeMillis();
        if(all!=null && now<allExpires) return all;
        allExpires=now+plugin.settings().cacheSeconds()*1000L;
        long from=now-plugin.settings().windowHours()*3600000L;
        all=plugin.db().read(c->Repository.prices(c,from));
        return all;
    }
    public CompletableFuture<History> history(String key) {
        long now=System.currentTimeMillis(); Entry cached=history.get(key);
        if(cached!=null && cached.expires()>now) return cached.future();
        long from=now-plugin.settings().windowHours()*3600000L,bucket=plugin.settings().chartBucket();
        CompletableFuture<History> future=plugin.db().read(c->Repository.history(c,key,from,bucket));
        history.put(key,new Entry(now+plugin.settings().cacheSeconds()*1000L,future));
        if(history.size()>128) history.remove(history.keySet().iterator().next());
        return future;
    }
    public void invalidate() { all=null; history.clear(); }
}

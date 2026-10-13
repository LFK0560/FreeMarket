package com.freemarket.market.config;

import com.freemarket.market.util.Money;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;

/** 配置在主线程验证后生成不可变快照，异步业务只接收值。 */
public record Settings(Material currency, boolean allowCurrencyMeta, long chunkCost, int defaultTax,
                       int maxChunks, Set<String> environments, int defaultHours, int maxHours,
                       Map<String,Integer> categoryHours, int defaultLimit, Map<String,Integer> groupLimits,
                       int maxStack, int cooldown, boolean blockCurrency, long minPrice, long maxPrice,
                       double deviation, int delay, int scanSeconds, boolean blacklistEnabled,
                       Set<String> blacklist, boolean blockMeta, boolean blockUnstackable,
                       int chartPoints, long chartBucket, int windowHours, int minTrades, int cacheSeconds,
                       String blocks, int keepDays, int pageSize, boolean listingBroadcast,
                       boolean tradeBroadcast, boolean sound, Map<String,Rule> categories) {
    public record Rule(Set<String> materials,List<String> suffixes) {}
    public static Settings load(YamlConfiguration c,YamlConfiguration cats) {
        Material currency=Material.matchMaterial(c.getString("currency.material","DIAMOND"));
        if (currency==null || !currency.isItem() || currency.isAir()) throw new IllegalArgumentException("货币必须是有效物品");
        int hours=positive(c,"listing.default-duration-hours",72), maxHours=positive(c,"listing.max-duration-hours",168);
        if (hours>maxHours || maxHours>87600) throw new IllegalArgumentException("上架时长配置无效");
        long min=Money.parse(c.getString("price.min-unit-price","0.01")), max=Money.parse(c.getString("price.max-unit-price","0"));
        if (min<1 || (max>0 && max<min)) throw new IllegalArgumentException("单价范围配置无效");
        int tax=Math.toIntExact(Money.parse(c.getString("market.default-tax-percent","5")));
        if (tax>10000) throw new IllegalArgumentException("税率不能超过百分之一百");
        int points=positive(c,"chart.points",24),page=positive(c,"general.page-size",36);
        if (points>40 || points<2 || page>36 || page%9!=0) throw new IllegalArgumentException("图表点数或分页大小配置无效");
        String blocks=c.getString("chart.blocks","▁▂▃▄▅▆▇█");
        if (blocks.codePointCount(0,blocks.length())<2) throw new IllegalArgumentException("图表至少需要两个阶梯字符");
        Map<String,Rule> rules=new LinkedHashMap<>();
        ConfigurationSection root=cats.getConfigurationSection("categories");
        if (root!=null) for (String key:root.getKeys(false)) rules.put(key,new Rule(Set.copyOf(root.getStringList(key+".materials")),List.copyOf(root.getStringList(key+".suffixes"))));
        Map<String,Integer> durations=ints(c,"listing.category-duration-hours");
        for (int h:durations.values()) if (h<1 || h>maxHours) throw new IllegalArgumentException("分类时长配置无效");
        Map<String,Integer> limits=ints(c,"listing.max-active-listings-by-group");
        for (int limit:limits.values()) if (limit<1) throw new IllegalArgumentException("挂单上限配置无效");
        int stack=positive(c,"listing.max-stack-amount",64);
        if (stack>64) throw new IllegalArgumentException("单笔挂单不能超过六十四件");
        return new Settings(currency,c.getBoolean("currency.allow-custom-nbt"),Money.parse(c.getString("market.chunk-cost","64")),
            tax,nonnegative(c,"market.max-chunks-per-owner",0),Set.copyOf(c.getStringList("market.allowed-environments")),
            hours,maxHours,Map.copyOf(durations),positive(c,"listing.max-active-listings-default",10),Map.copyOf(limits),
            stack,nonnegative(c,"listing.cooldown-seconds",3),c.getBoolean("listing.block-currency",true),min,max,
            c.getDouble("price.deviation-warn-percent",300),nonnegative(c,"delivery.delay-seconds",10),
            positive(c,"delivery.expire-scan-seconds",60),c.getBoolean("blacklist.enabled",true),Set.copyOf(c.getStringList("blacklist.materials")),
            c.getBoolean("blacklist.block-all-custom-nbt"),c.getBoolean("blacklist.block-unstackable"),
            points,c.getString("chart.granularity","DAY").equalsIgnoreCase("HOUR")?3600000L:86400000L,
            positive(c,"chart.window-hours",720),positive(c,"chart.min-trades",2),positive(c,"chart.cache-seconds",60),
            blocks,positive(c,"storage.keep-trade-days",90),page,c.getBoolean("general.broadcast-new-listing"),
            c.getBoolean("general.broadcast-trade"),c.getBoolean("general.sound-enabled",true),Collections.unmodifiableMap(rules));
    }
    private static int positive(YamlConfiguration c,String key,int fallback) {
        int n=c.getInt(key,fallback); if (n<1) throw new IllegalArgumentException("配置必须为正数："+key); return n;
    }
    private static int nonnegative(YamlConfiguration c,String key,int fallback) {
        int n=c.getInt(key,fallback); if (n<0) throw new IllegalArgumentException("配置不能为负数："+key); return n;
    }
    private static Map<String,Integer> ints(YamlConfiguration c,String key) {
        Map<String,Integer> result=new LinkedHashMap<>(); ConfigurationSection s=c.getConfigurationSection(key);
        if (s!=null) for (String k:s.getKeys(false)) result.put(k,s.getInt(k)); return result;
    }
    public String category(Material material) {
        for (var e:categories.entrySet()) {
            if (e.getValue().materials().contains(material.name()) || e.getValue().suffixes().stream().anyMatch(material.name()::endsWith)) return e.getKey();
        }
        return "misc";
    }
}

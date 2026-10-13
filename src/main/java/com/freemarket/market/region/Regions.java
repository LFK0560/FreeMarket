package com.freemarket.market.region;

import com.freemarket.market.model.Models.MarketChunk;
import org.bukkit.Location;
import java.util.*;

/** 使用世界 UUID 和整数区块坐标，不加载区块、不扫描玩家移动。 */
public final class Regions {
    private List<MarketChunk> chunks=List.of();
    public void set(List<MarketChunk> chunks) { this.chunks=List.copyOf(chunks); }
    public Optional<MarketChunk> at(Location l) {
        if(l.getWorld()==null) return Optional.empty();
        String world=l.getWorld().getUID().toString(); int x=l.getBlockX()>>4,z=l.getBlockZ()>>4;
        return chunks.stream().filter(c->c.worldId().equals(world)&&c.x()==x&&c.z()==z).findFirst();
    }
    public Optional<MarketChunk> nearest(Location l,String marketId) {
        String world=l.getWorld()==null?"":l.getWorld().getUID().toString();
        return chunks.stream().filter(c->marketId==null||c.marketId().equals(marketId))
            .min(Comparator.comparingDouble((MarketChunk c)->c.worldId().equals(world)?distance(c,l):Double.POSITIVE_INFINITY)
                .thenComparing(MarketChunk::worldName).thenComparingInt(MarketChunk::x).thenComparingInt(MarketChunk::z));
    }
    private static double distance(MarketChunk c,Location l) {
        double x=c.x()*16.0+8-l.getX(),z=c.z()*16.0+8-l.getZ(); return x*x+z*z;
    }
}

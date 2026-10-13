package com.freemarket.market.market;

import com.freemarket.market.FreeMarket;
import com.freemarket.market.config.Settings;
import com.freemarket.market.model.Models.*;
import com.freemarket.market.storage.*;
import com.freemarket.market.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;
import org.bukkit.block.ShulkerBox;
import java.util.*;
import java.util.concurrent.*;

/** 在主线程捕获与变更背包，通过未来对象编排串行数据库事务。 */
public final class MarketService {
    public record Selection(int slot,ItemStack expected,int amount,long price,int hours) {}
    private final FreeMarket plugin;
    private final Set<UUID> busy=new HashSet<>();
    private final Map<UUID,Long> lastSell=new HashMap<>();
    public MarketService(FreeMarket plugin) { this.plugin=plugin; }
    public boolean busy(Player p) { return busy.contains(p.getUniqueId()); }
    private void begin(Player p) {
        InventoryUtil.main();
        if(!busy.add(p.getUniqueId())) throw new MarketException("msg.busy");
    }
    private <T> CompletableFuture<T> change(Player p,Database.Work<T> work) {
        begin(p); CompletableFuture<T> result=new CompletableFuture<>();
        plugin.db().transaction(work).whenComplete((value,error)->plugin.sync(()->{
            busy.remove(p.getUniqueId());
            if(error!=null) result.completeExceptionally(error); else { plugin.prices().invalidate(); result.complete(value); }
        }));
        return result;
    }
    public CompletableFuture<Snapshot> snapshot(Player p) {
        String uuid=p.getUniqueId().toString(),name=p.getName(); long now=System.currentTimeMillis();
        return plugin.db().transaction(c->{ Repository.touch(c,uuid,name); return Repository.snapshot(c,uuid,now); });
    }
    public void permission(Player p,String permission) {
        if(!p.hasPermission(permission)) throw new MarketException("msg.permission");
    }
    public MarketChunk requireRegion(Player p,String marketId) {
        MarketChunk chunk=plugin.regions().at(p.getLocation()).orElseThrow(()->new MarketException("msg.region"));
        if(marketId!=null && !chunk.marketId().equals(marketId)) throw new MarketException("msg.pickup-region");
        return chunk;
    }
    private void regionInDatabase(java.sql.Connection c,MarketChunk chunk) throws Exception {
        if(Repository.query(c,"SELECT market_id FROM chunks WHERE world_id=? AND x=? AND z=?",r->r.getString(1),chunk.worldId(),chunk.x(),chunk.z())
            .stream().noneMatch(chunk.marketId()::equals)) throw new MarketException("msg.region");
    }
    public CompletableFuture<MarketChunk> buyChunk(Player p,long expectedFee) {
        Settings s=plugin.settings();
        if(expectedFee!=s.chunkCost()) throw new MarketException("msg.changed");
        Location l=p.getLocation();
        if(!s.environments().contains(l.getWorld().getEnvironment().name())) throw new MarketException("msg.dimension");
        String uuid=p.getUniqueId().toString(),name=p.getName(),world=l.getWorld().getUID().toString(),worldName=l.getWorld().getName(),env=l.getWorld().getEnvironment().name();
        int x=l.getBlockX()>>4,z=l.getBlockZ()>>4,y=l.getBlockY();
        return change(p,c->Repository.buyChunk(c,uuid,name,world,worldName,env,x,z,y,s.chunkCost(),s.defaultTax(),s.maxChunks()));
    }
    public CompletableFuture<Trade> buy(Player p,Listing l,int amount) {
        permission(p,"freemarket.buy"); String uuid=p.getUniqueId().toString(); long delay=plugin.settings().delay()*1000L;
        return change(p,c->Repository.buy(c,uuid,l.id(),amount,l.price(),System.currentTimeMillis(),delay));
    }
    public CompletableFuture<Void> reprice(Player p,Listing l,long price) {
        permission(p,"freemarket.sell"); MarketChunk chunk=requireRegion(p,null); validatePrice(price);
        String uuid=p.getUniqueId().toString();
        return change(p,c->{ regionInDatabase(c,chunk); Repository.reprice(c,uuid,l.id(),price,System.currentTimeMillis()); return null; });
    }
    public CompletableFuture<Void> cancel(Player p,Listing l) {
        permission(p,"freemarket.sell"); MarketChunk chunk=requireRegion(p,null);
        String uuid=p.getUniqueId().toString(); long now=System.currentTimeMillis();
        return change(p,c->{ regionInDatabase(c,chunk); Repository.cancel(c,uuid,l.id(),now); return null; });
    }
    public void validatePrice(long price) {
        Settings s=plugin.settings();
        if(price<s.minPrice() || (s.maxPrice()>0 && price>s.maxPrice())) throw new MarketException("msg.price");
    }
    public CompletableFuture<Void> deposit(Player p,int amount) {
        if(amount<1) throw new MarketException("msg.number");
        Settings s=plugin.settings(); ItemStack[] before=InventoryUtil.snapshot(p);
        ItemStack[] after=InventoryUtil.takeCurrency(before,s.currency(),s.allowCurrencyMeta(),amount);
        return physical(p,"DEPOSIT",Math.multiplyExact((long)amount,100),null,null,before,after,List.of(),null);
    }
    public CompletableFuture<Void> withdraw(Player p,int amount) {
        if(amount<1) throw new MarketException("msg.number");
        ItemStack[] before=InventoryUtil.snapshot(p);
        ItemStack[] after=InventoryUtil.give(before,ItemStack.of(plugin.settings().currency()),amount);
        return physical(p,"WITHDRAW",Math.multiplyExact((long)amount,100),null,null,before,after,List.of(),null);
    }
    public CompletableFuture<Void> sell(Player p,List<Selection> selections) {
        permission(p,"freemarket.sell"); MarketChunk chunk=requireRegion(p,null); Settings s=plugin.settings();
        long now=System.currentTimeMillis();
        if(now-lastSell.getOrDefault(p.getUniqueId(),0L)<s.cooldown()*1000L) throw new MarketException("msg.cooldown");
        ItemStack[] before=InventoryUtil.snapshot(p),after=InventoryUtil.copy(before);
        List<Draft> drafts=new ArrayList<>(); Set<Integer> used=new HashSet<>();
        for(Selection selection:selections) {
            if(selection.slot()<0 || selection.slot()>=before.length || !used.add(selection.slot())) throw new MarketException("msg.number");
            ItemStack item=before[selection.slot()];
            if(InventoryUtil.empty(item) || !item.isSimilar(selection.expected()) || item.getAmount()!=selection.expected().getAmount())
                throw new MarketException("msg.inventory-changed");
            if(selection.amount()<1 || selection.amount()>Math.min(s.maxStack(),item.getAmount()) || selection.hours()<1 || selection.hours()>s.maxHours())
                throw new MarketException("msg.number");
            validatePrice(selection.price()); Money.total(selection.price(),selection.amount());
            validateItem(p,item,0);
            String category=s.category(item.getType());
            String search=item.getType().name()+" "+PlainTextComponentSerializer.plainText().serialize(item.effectiveName())+" "+plugin.text().plain("item-names."+item.getType().name());
            drafts.add(new Draft(selection.slot(),InventoryUtil.unitBytes(item),InventoryUtil.key(item),category,search,selection.amount(),selection.price(),selection.hours()*3600000L));
            after[selection.slot()].setAmount(item.getAmount()-selection.amount());
        }
        CompletableFuture<Void> result=physical(p,"SELL",0,chunk.marketId(),null,before,after,drafts,chunk);
        result.thenRun(()->lastSell.put(p.getUniqueId(),System.currentTimeMillis()));
        return result;
    }
    private void validateItem(Player p,ItemStack item,int depth) {
        if(InventoryUtil.empty(item)) return;
        Settings s=plugin.settings();
        if(depth>8 || item.getPersistentDataContainer().has(new NamespacedKey(plugin,"gui_action"))) throw new MarketException("msg.blacklisted");
        if(s.blockCurrency() && item.getType()==s.currency()) throw new MarketException("msg.currency-product");
        if(!p.hasPermission("freemarket.bypass.blacklist") && s.blacklistEnabled() &&
            (s.blacklist().contains(item.getType().name()) || (s.blockMeta()&&item.hasItemMeta()) || (s.blockUnstackable()&&item.getMaxStackSize()==1)))
            throw new MarketException("msg.blacklisted");
        if(item.getItemMeta() instanceof BundleMeta bundle) for(ItemStack nested:bundle.getItems()) validateItem(p,nested,depth+1);
        if(item.getItemMeta() instanceof BlockStateMeta block && block.hasBlockState() && block.getBlockState() instanceof ShulkerBox box)
            for(ItemStack nested:box.getInventory().getContents()) validateItem(p,nested,depth+1);
    }
    public CompletableFuture<Void> claim(Player p,Goods goods) {
        MarketChunk chunk=requireRegion(p,goods.marketId());
        if(goods.readyAt()>System.currentTimeMillis()) throw new MarketException("msg.delivery-wait");
        ItemStack[] before=InventoryUtil.snapshot(p);
        ItemStack item=ItemStack.deserializeBytes(goods.item());
        ItemStack[] after=InventoryUtil.give(before,item,goods.amount());
        return physical(p,"CLAIM",0,goods.marketId(),goods.id(),before,after,List.of(),chunk);
    }
    private CompletableFuture<Void> physical(Player p,String kind,long cents,String marketId,Long goodsId,
                                            ItemStack[] before,ItemStack[] after,List<Draft> drafts,MarketChunk chunk) {
        begin(p); Settings s=plugin.settings();
        int limit=s.defaultLimit();
        for(var group:s.groupLimits().entrySet()) if(p.hasPermission("freemarket.limit."+group.getKey())) limit=Math.max(limit,group.getValue());
        if(p.hasPermission("freemarket.bypass.limit")) limit=Integer.MAX_VALUE;
        int finalLimit=limit;
        Operation operation=new Operation(UUID.randomUUID().toString(),p.getUniqueId().toString(),p.getName(),marketId,kind,"PREPARED",cents,
            s.currency().name(),goodsId,InventoryUtil.bytes(before),InventoryUtil.bytes(after),System.currentTimeMillis(),"");
        CompletableFuture<Void> result=new CompletableFuture<>();
        plugin.db().transaction(c->{
            if(chunk!=null) regionInDatabase(c,chunk);
            Repository.prepare(c,operation,drafts,finalLimit,s.minPrice(),s.maxPrice(),s.maxHours()*3600000L); return null;
        }).whenComplete((unused,error)->plugin.sync(()->{
            if(error!=null) { abortKnownUnapplied(p,operation,result,error); return; }
            boolean started=false;
            try {
                if(!p.isOnline() || !InventoryUtil.equal(before,InventoryUtil.snapshot(p))) throw new MarketException("msg.inventory-changed");
                if(chunk!=null) requireRegion(p,kind.equals("CLAIM")?marketId:null);
                started=true;
                p.getInventory().setStorageContents(InventoryUtil.copy(after));
                if(!InventoryUtil.equal(after,InventoryUtil.snapshot(p))) throw new IllegalStateException("实物操作后背包与预期不一致");
                p.saveData();
                plugin.db().transaction(c->{ Repository.finish(c,operation.id(),System.currentTimeMillis()); return null; })
                    .whenComplete((ignored,finishError)->plugin.sync(()->{
                        if(finishError!=null) freeze(p,operation,result,finishError);
                        else { busy.remove(p.getUniqueId()); plugin.prices().invalidate(); result.complete(null); }
                    }));
            } catch(Exception e) {
                if(started) freeze(p,operation,result,e); else abortKnownUnapplied(p,operation,result,e);
            }
        }));
        return result;
    }
    private void abortKnownUnapplied(Player p,Operation operation,CompletableFuture<Void> result,Throwable error) {
        plugin.db().transaction(c->{ Repository.abort(c,operation.id()); return null; }).whenComplete((v,abortError)->plugin.sync(()->{
            busy.remove(p.getUniqueId());
            if(abortError!=null) { plugin.logError(abortError); result.completeExceptionally(new MarketException("msg.frozen")); }
            else result.completeExceptionally(error);
        }));
    }
    private void freeze(Player p,Operation operation,CompletableFuture<Void> result,Throwable error) {
        plugin.logError(error);
        plugin.db().transaction(c->{ Repository.freeze(c,operation.id(),"实物操作完成状态需要人工核查"); return null; })
            .whenComplete((v,freezeError)->plugin.sync(()->{
                busy.remove(p.getUniqueId()); if(freezeError!=null) plugin.logError(freezeError);
                result.completeExceptionally(new MarketException("msg.frozen"));
            }));
    }
    public CompletableFuture<Void> favorite(Player p,String key) {
        permission(p,"freemarket.favorite"); String uuid=p.getUniqueId().toString();
        return change(p,c->{ Repository.favorite(c,uuid,key); return null; });
    }
    public CompletableFuture<Void> tax(Player p,Market m,int bps) {
        String uuid=p.getUniqueId().toString();
        return change(p,c->{ Repository.setTax(c,uuid,m.id(),bps); return null; });
    }
    public CompletableFuture<Long> claimTax(Player p,Market m) {
        String uuid=p.getUniqueId().toString(); return change(p,c->Repository.claimTax(c,uuid,m.id()));
    }
    public CompletableFuture<Void> close(Player p,Market m) {
        String uuid=p.getUniqueId().toString(); return change(p,c->{ Repository.closeMarket(c,uuid,m.id()); return null; });
    }
    public CompletableFuture<Void> remove(Player p,MarketChunk chunk) {
        String uuid=p.getUniqueId().toString(); return change(p,c->{ Repository.removeChunk(c,uuid,chunk); return null; });
    }
    public CompletableFuture<Void> resolve(Player p,String id,boolean done) {
        if(!plugin.admin(p)) throw new MarketException("msg.not-admin");
        String uuid=p.getUniqueId().toString(); long now=System.currentTimeMillis();
        return change(p,c->{ Repository.resolve(c,uuid,id,done,now); return null; });
    }
}

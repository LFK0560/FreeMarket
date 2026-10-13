package com.freemarket.market.gui;

import com.freemarket.market.FreeMarket;
import com.freemarket.market.chart.Sparkline;
import com.freemarket.market.config.*;
import com.freemarket.market.market.MarketService.Selection;
import com.freemarket.market.model.Models.*;
import com.freemarket.market.storage.*;
import com.freemarket.market.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** 所有菜单均为展示副本；按槽位和菜单令牌分发，不接受光标物品上的动作。 */
public final class GuiManager {
    @FunctionalInterface private interface Action { void run(ClickType click); }
    private record Browse(String category,String search,int sort,int page,boolean mine,boolean favorites) {}
    private record View(Snapshot snapshot,Map<String,PriceStats> prices) {}
    private record Rule(String key,boolean integer) {}
    private final FreeMarket plugin;
    private final Texts t;
    private final NamespacedKey actionKey;
    private final Map<UUID,Long> generations=new HashMap<>();
    private final Set<UUID> opening=new HashSet<>();
    private final AnvilInput input;
    private final DateTimeFormatter dates=DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    public GuiManager(FreeMarket plugin) {
        this.plugin=plugin; t=plugin.text(); actionKey=new NamespacedKey(plugin,"gui_action"); input=new AnvilInput(plugin,this);
    }
    private final class Menu implements InventoryHolder {
        private final Inventory inventory;
        private final UUID owner;
        private final String token=UUID.randomUUID().toString();
        private final Map<Integer,Action> actions=new HashMap<>();
        private final boolean adminOnly;
        private IntConsumer backpack;
        Menu(Player p,String title,Object... pairs) {
            owner=p.getUniqueId(); inventory=Bukkit.createInventory(this,54,t.get(title,pairs));
            adminOnly=Set.of("title.admin","title.all-chunks","title.rules","title.blacklist","title.limits","title.staff","title.frozen","title.operation","title.inventory-preview","title.recovery").contains(title);
        }
        @Override public Inventory getInventory() { return inventory; }
        void put(int slot,ItemStack source,Action action) {
            ItemStack copy=source.clone();
            copy.editPersistentDataContainer(data->data.set(actionKey,PersistentDataType.STRING,token+":"+slot));
            inventory.setItem(slot,copy); if(action!=null) actions.put(slot,action);
        }
        void button(int slot,Material type,String name,Action action,String... lore) { put(slot,icon(type,name,lore),action); }
        void show(Player p) { openGuard(p,()->p.openInventory(inventory)); }
    }
    public void openGuard(Player p,Runnable action) {
        opening.add(p.getUniqueId()); try { action.run(); } finally { opening.remove(p.getUniqueId()); }
    }
    private ItemStack icon(Material type,String name,String... lore) {
        ItemStack item=ItemStack.of(type);
        item.editMeta(meta->{ meta.displayName(t.get(name)); meta.lore(Arrays.stream(lore).map(t::get).toList()); });
        return item;
    }
    private ItemStack custom(Material type,Component name,Component... lore) {
        ItemStack item=ItemStack.of(type); item.editMeta(meta->{meta.displayName(name);meta.lore(List.of(lore));}); return item;
    }
    private ItemStack withLore(ItemStack original,List<Component> added) {
        ItemStack item=original.clone();
        item.editMeta(meta->{ List<Component> lore=new ArrayList<>(); if(meta.lore()!=null) lore.addAll(meta.lore()); lore.addAll(added); meta.lore(lore); });
        return item;
    }
    private void back(Menu menu,Player p,Runnable target) {
        menu.button(49,Material.ARROW,"ui.back",c->target.run());
        menu.button(52,Material.BARRIER,"ui.close",c->p.closeInventory());
    }
    private void pages(Menu menu,int page,int size,int total,IntConsumer open) {
        if(page>0) menu.button(45,Material.ARROW,"ui.previous",c->open.accept(page-1));
        if((page+1)*size<total) menu.button(53,Material.ARROW,"ui.next",c->open.accept(page+1));
    }
    private <E> List<E> slice(List<E> list,int page,int size) {
        int start=Math.min(Math.max(0,page)*size,list.size()); return list.subList(start,Math.min(list.size(),start+size));
    }
    private void load(Player p,Consumer<View> render) {
        if(!plugin.ready()) { t.send(p,"msg.loading"); return; }
        plugin.service().permission(p,"freemarket.use");
        long request=generations.merge(p.getUniqueId(),1L,Long::sum);
        plugin.service().snapshot(p).thenCombine(plugin.prices().all(),View::new).whenComplete((view,error)->plugin.sync(()->{
            if(!p.isOnline() || generations.getOrDefault(p.getUniqueId(),0L)!=request) return;
            if(error!=null) { plugin.error(p,error); return; }
            plugin.regions().set(view.snapshot().chunks());
            try { render.accept(view); } catch(Exception e) { plugin.error(p,e); }
        }));
    }
    private <T> void watch(Player p,CompletableFuture<T> future,Consumer<T> success,Runnable refresh) {
        Inventory previous=p.getOpenInventory().getTopInventory();
        long generation=generations.getOrDefault(p.getUniqueId(),0L);
        future.whenComplete((value,error)->plugin.sync(()->{
            if(!p.isOnline()) return;
            if(error!=null) {
                plugin.error(p,error);
                if(p.getOpenInventory().getTopInventory()==previous && generations.getOrDefault(p.getUniqueId(),0L)==generation) refresh.run();
                return;
            }
            success.accept(value);
            if(p.getOpenInventory().getTopInventory()==previous && generations.getOrDefault(p.getUniqueId(),0L)==generation) refresh.run();
        }));
    }
    private void changed(Player p,CompletableFuture<?> future,Runnable refresh) {
        watch(p,future,v->t.send(p,"msg.saved"),refresh);
    }
    private void ask(Player p,String title,String initial,Consumer<String> submit,Runnable cancel) {
        input.open(p,title,initial,submit,cancel);
    }
    private void confirm(Player p,String title,Component description,Runnable yes,Runnable no) {
        Menu menu=new Menu(p,title);
        boolean[] submitted={false};
        menu.put(22,custom(Material.PAPER,t.get("ui.confirm-description"),description),null);
        menu.button(29,Material.LIME_CONCRETE,"ui.confirm",c->{if(!submitted[0]) {submitted[0]=true;yes.run();}});
        menu.button(33,Material.RED_CONCRETE,"ui.cancel",c->no.run());
        menu.show(p);
    }
    public void market(Player p) { browse(p,new Browse("all","",0,0,false,false)); }
    public void my(Player p) { plugin.service().permission(p,"freemarket.sell"); browse(p,new Browse("all","",0,0,true,false)); }
    public void favorites(Player p) { plugin.service().permission(p,"freemarket.favorite"); browse(p,new Browse("all","",0,0,false,true)); }
    private void browse(Player p,Browse b) {
        load(p,view->{
            Snapshot s=view.snapshot(); String uuid=p.getUniqueId().toString();
            Comparator<Listing> comparator=switch(b.sort()) {
                case 1->Comparator.comparingLong(Listing::price).reversed();
                case 2->Comparator.comparingLong(Listing::created).reversed();
                case 3->Comparator.comparingLong(Listing::expires);
                case 4->Comparator.comparingInt(Listing::sold).reversed();
                default->Comparator.comparingLong(Listing::price);
            };
            List<Listing> list=s.listings().stream()
                .filter(l->!b.mine()||l.seller().equals(uuid))
                .filter(l->!b.favorites()||s.favorites().contains(l.itemKey()))
                .filter(l->b.category().equals("all")||l.category().equals(b.category()))
                .filter(l->b.search().isBlank()||l.search().toLowerCase(Locale.ROOT).contains(b.search().toLowerCase(Locale.ROOT)))
                .sorted(comparator.thenComparingLong(Listing::id)).toList();
            int size=plugin.settings().pageSize(),page=Math.min(b.page(),Math.max(0,(list.size()-1)/size));
            Menu menu=new Menu(p,b.mine()?"title.my":b.favorites()?"title.favorites":"title.market","page",page+1);
            String[] cats={"all","building","minerals","combat","tools","food","enchanted_book","misc"};
            Material[] types={Material.CHEST,Material.BRICKS,Material.IRON_INGOT,Material.DIAMOND_SWORD,Material.IRON_PICKAXE,Material.BREAD,Material.ENCHANTED_BOOK,Material.PAPER};
            for(int i=0;i<cats.length;i++) {
                String category=cats[i]; menu.button(i,types[i],"category."+category,c->browse(p,new Browse(category,b.search(),b.sort(),0,b.mine(),b.favorites())),"ui.category-help");
            }
            menu.button(8,Material.NETHER_STAR,"ui.favorites",c->favorites(p));
            int slot=9;
            for(Listing l:slice(list,page,size)) {
                ItemStack item=ItemStack.deserializeBytes(l.item()); item.setAmount(Math.min(l.remaining(),item.getMaxStackSize()));
                List<Component> lore=listingLore(p,s,l,view.prices().get(l.itemKey()));
                lore.add(t.get(b.mine()?"ui.my-help":"ui.listing-help"));
                menu.put(slot++,withLore(item,lore),click->{
                    if(b.mine() && click==ClickType.SHIFT_RIGHT) {
                        confirm(p,"title.cancel-listing",t.get("ui.cancel-listing-description","id",l.id()),
                            ()->changed(p,plugin.service().cancel(p,l),()->browse(p,b)),()->browse(p,b));
                    } else if(b.mine() && click.isRightClick()) {
                        ask(p,"input.price",Money.format(l.price()),value->changed(p,plugin.service().reprice(p,l,Money.parse(value)),()->browse(p,b)),()->browse(p,b));
                    } else if(click.isRightClick()) changed(p,plugin.service().favorite(p,l.itemKey()),()->browse(p,b));
                    else detail(p,l.id(),()->browse(p,b));
                });
            }
            pages(menu,page,size,list.size(),n->browse(p,new Browse(b.category(),b.search(),b.sort(),n,b.mine(),b.favorites())));
            menu.put(46,custom(Material.HOPPER,t.get("ui.sort","value",t.get("sort."+b.sort()))),c->browse(p,new Browse(b.category(),b.search(),(b.sort()+1)%5,0,b.mine(),b.favorites())));
            menu.button(47,Material.COMPASS,"ui.search",c->{
                if(c.isRightClick()) browse(p,b);
                else ask(p,"input.search",b.search(),value->browse(p,new Browse(b.category(),value,b.sort(),0,b.mine(),b.favorites())),()->browse(p,b));
            },"ui.search-help");
            menu.button(48,Material.BARREL,"ui.my",c->my(p));
            menu.button(49,Material.CHEST_MINECART,"ui.pending",c->pending(p,0));
            menu.button(50,plugin.settings().currency(),"ui.wallet",c->wallet(p));
            menu.button(51,Material.GRASS_BLOCK,"ui.my-market",c->owner(p));
            menu.button(52,Material.EMERALD,"ui.sell",c->sell(p,new LinkedHashMap<>(),null));
            plugin.regions().nearest(p.getLocation(),null).ifPresent(chunk->menu.put(0,
                custom(Material.CHEST,t.get("category.all"),coordinates(chunk),t.get("ui.nearest-help")),
                click->{if(click.isRightClick()) sendCoordinates(p,chunk);else browse(p,new Browse("all",b.search(),b.sort(),0,b.mine(),b.favorites()));}));
            menu.show(p);
        });
    }
    private List<Component> listingLore(Player p,Snapshot s,Listing l,PriceStats stats) {
        Market m=s.market(l.marketId());
        List<Component> lore=new ArrayList<>();
        lore.add(t.get("lore.seller","name",l.sellerName()));
        lore.add(t.get("lore.price","price",Money.format(l.price()),"amount",l.remaining(),"total",Money.format(Money.total(l.price(),l.remaining()))));
        lore.add(t.get("lore.market","name",m==null?"":m.ownerName()));
        lore.add(t.get("lore.tax","tax",Money.format(l.taxBps())));
        lore.add(t.get("lore.time","time",remaining(l.expires())));
        lore.add(t.get("lore.sold","amount",l.sold()));
        if(l.seller().equals(p.getUniqueId().toString())) lore.add(t.get("lore.income","amount",Money.format(l.income())));
        if(stats!=null) {
            lore.add(t.get("chart.average","avg",decimal(stats.average()),"hours",plugin.settings().windowHours()));
            double change=stats.average()==0?0:(l.price()/100.0-stats.average())/stats.average()*100;
            lore.add(t.get(change>=0?"chart.deviation-up":"chart.deviation-down","change",decimal(Math.abs(change))));
        } else lore.add(t.get("chart.no-data"));
        return lore;
    }
    private void nearest(Menu menu,int slot,Player p,String marketId) {
        Optional<MarketChunk> nearest=plugin.regions().nearest(p.getLocation(),marketId);
        if(nearest.isPresent()) {
            MarketChunk c=nearest.get();
            menu.put(slot,custom(Material.FILLED_MAP,t.get("ui.nearest"),coordinates(c),t.get("ui.coords-help")),click->sendCoordinates(p,c));
        } else menu.button(slot,Material.MAP,"ui.no-market",null);
    }
    private Component coordinates(MarketChunk c) {
        return t.get("lore.coordinates","world",c.worldName(),"dimension",t.get("dimension."+c.environment()),"x",c.x()*16+8,"y",c.y(),"z",c.z()*16+8);
    }
    private void sendCoordinates(Player p,MarketChunk c) { p.sendMessage(t.get("prefix").append(coordinates(c))); }
    private String remaining(long until) {
        long seconds=Math.max(0,(until-System.currentTimeMillis()+999)/1000);
        return t.plain("time.remaining","days",seconds/86400,"hours",seconds/3600%24,"minutes",seconds/60%60,"seconds",seconds%60);
    }
    private static String decimal(double d) { return String.format(Locale.ROOT,"%.2f",d); }
    private void detail(Player p,long id,Runnable returnTo) {
        load(p,view->{
            Listing listing=view.snapshot().listings().stream().filter(l->l.id()==id).findFirst().orElseThrow(()->new MarketException("msg.sold"));
            Inventory current=p.getOpenInventory().getTopInventory();
            plugin.prices().history(listing.itemKey()).whenComplete((history,error)->plugin.sync(()->{
                if(!p.isOnline() || p.getOpenInventory().getTopInventory()!=current) return;
                if(error!=null) { plugin.error(p,error); return; }
                Menu menu=new Menu(p,"title.detail");
                ItemStack item=ItemStack.deserializeBytes(listing.item()); item.setAmount(1);
                List<Component> lore=listingLore(p,view.snapshot(),listing,history.stats());
                lore.add(t.get("lore.balance","balance",Money.format(view.snapshot().account().balance())));
                lore.add(t.get("lore.delivery","seconds",plugin.settings().delay()));
                menu.put(22,withLore(item,lore),null);
                Sparkline.Result spark=Sparkline.render(history.points(),plugin.settings().chartPoints(),plugin.settings().blocks());
                List<Component> chart=chartLore(history,spark);
                menu.put(13,withLore(icon(Material.WRITTEN_BOOK,"ui.chart"),chart),c->book(p,history,spark));
                int[] slots={10,11,12,14,15,16,19,20,24,25};
                List<PricePoint> bars=Sparkline.compress(history.points(),10);
                double min=history.stats().min(),max=history.stats().max();
                for(int i=0;i<bars.size();i++) {
                    PricePoint point=bars.get(i);
                    int level=max-min<1e-9?4:Math.max(1,Math.min(8,1+(int)Math.round((point.price()-min)/(max-min)*7)));
                    ItemStack bar=custom(spark.change()>=0?Material.GREEN_STAINED_GLASS_PANE:Material.RED_STAINED_GLASS_PANE,
                        t.get("chart.point","price",decimal(point.price())),t.get("chart.volume","amount",point.volume()));
                    bar.setAmount(level); menu.put(slots[i],bar,null);
                }
                menu.button(29,Material.GOLD_NUGGET,"ui.buy-one",c->purchase(p,listing,1,returnTo));
                menu.button(31,Material.GOLD_INGOT,"ui.buy-sixteen",c->purchase(p,listing,Math.min(16,listing.remaining()),returnTo));
                menu.button(33,Material.GOLD_BLOCK,"ui.buy-all",c->purchase(p,listing,listing.remaining(),returnTo));
                menu.button(35,Material.PAPER,"ui.buy-custom",c->ask(p,"input.quantity","1",value->purchase(p,listing,Integer.parseInt(value.trim()),returnTo),()->detail(p,id,returnTo)));
                menu.button(40,Material.NETHER_STAR,"ui.favorites",c->changed(p,plugin.service().favorite(p,listing.itemKey()),()->detail(p,id,returnTo)));
                nearest(menu,43,p,listing.marketId()); back(menu,p,returnTo); menu.show(p);
            }));
        });
    }
    private List<Component> chartLore(History history,Sparkline.Result spark) {
        if(history.stats().trades()<plugin.settings().minTrades()) return List.of(t.get("chart.no-data"));
        return List.of(t.get(spark.change()<0?"chart.line-down":"chart.line-up","line",spark.line()),
            t.get(spark.change()>=0?"chart.up":"chart.down","change",decimal(Math.abs(spark.change()))),
            t.get("chart.average","avg",decimal(history.stats().average()),"hours",plugin.settings().windowHours()),
            t.get("chart.range","min",decimal(history.stats().min()),"max",decimal(history.stats().max())));
    }
    private void book(Player p,History history,Sparkline.Result spark) {
        ItemStack item=ItemStack.of(Material.WRITTEN_BOOK); BookMeta meta=(BookMeta)item.getItemMeta();
        meta.title(t.get("ui.chart")); meta.author(t.get("book.author"));
        List<Component> pages=new ArrayList<>();
        Component first=t.get("book.heading");
        for(Component line:chartLore(history,spark)) first=first.append(Component.newline()).append(line);
        pages.add(first);
        for(int index=0;index<history.recent().size();index+=4) {
            Component page=t.get("book.heading");
            for(Trade trade:history.recent().subList(index,Math.min(index+4,history.recent().size())))
                page=page.append(Component.newline()).append(t.get("book.trade","time",dates.format(Instant.ofEpochMilli(trade.time())),"amount",trade.amount(),"price",Money.format(trade.unitPrice())));
            pages.add(page);
        }
        meta.pages(pages); item.setItemMeta(meta); p.openBook(item);
    }
    private void purchase(Player p,Listing l,int amount,Runnable returnTo) {
        if(amount<1 || amount>l.remaining()) throw new MarketException("msg.number");
        long total=Money.total(l.price(),amount);
        confirm(p,"title.purchase",t.get("ui.purchase-description","amount",amount,"total",Money.format(total),"seconds",plugin.settings().delay()),()->{
            watch(p,plugin.service().buy(p,l,amount),trade->{
                t.send(p,"msg.bought","amount",trade.amount(),"total",Money.format(trade.total()),"seconds",plugin.settings().delay());
                Player seller=Bukkit.getPlayer(UUID.fromString(trade.seller()));
                if(seller!=null) t.send(seller,"msg.seller-income","amount",Money.format(trade.total()-trade.tax()));
                if(plugin.settings().tradeBroadcast()) Bukkit.broadcast(t.get("broadcast.trade","buyer",p.getName(),"amount",trade.amount()));
            },()->pending(p,0));
        },()->detail(p,l.id(),returnTo));
    }
    public void pending(Player p,int page) {
        load(p,view->{
            Snapshot s=view.snapshot(); List<Goods> list=s.goods(); int size=36;
            Menu menu=new Menu(p,"title.pending","page",page+1); int slot=0;
            for(Goods goods:slice(list,page,size)) {
                ItemStack item=ItemStack.deserializeBytes(goods.item()); item.setAmount(goods.amount());
                Market market=s.market(goods.marketId());
                List<Component> lore=new ArrayList<>();
                lore.add(t.get("lore.goods-reason","reason",t.get("reason."+goods.reason())));
                lore.add(t.get("lore.market","name",market==null?"":market.ownerName()));
                lore.add(t.get("lore.goods-wait","time",remaining(goods.readyAt())));
                plugin.regions().nearest(p.getLocation(),goods.marketId()).ifPresent(c->lore.add(coordinates(c)));
                lore.add(t.get(goods.status().equals("RESERVED")?"ui.reserved":"ui.pickup-help"));
                menu.put(slot++,withLore(item,lore),click->{
                    if(click.isRightClick()) plugin.regions().nearest(p.getLocation(),goods.marketId()).ifPresent(c->sendCoordinates(p,c));
                    else watch(p,plugin.service().claim(p,goods),v->{t.send(p,"msg.claimed"); sound(p);},()->pending(p,page));
                });
            }
            pages(menu,page,size,list.size(),n->pending(p,n));
            menu.button(47,Material.HOPPER,"ui.pickup-all",c->{
                String here=plugin.service().requireRegion(p,null).marketId();
                List<Goods> eligible=list.stream().filter(g->g.marketId().equals(here)&&g.status().equals("AVAILABLE")&&g.readyAt()<=System.currentTimeMillis()).toList();
                watch(p,claimSequence(p,eligible,0),v->t.send(p,"msg.claimed"),()->pending(p,0));
            });
            menu.button(50,plugin.settings().currency(),"ui.wallet",c->wallet(p));
            menu.button(51,Material.CLOCK,"ui.refresh",c->pending(p,page));
            back(menu,p,()->market(p)); menu.show(p);
        });
    }
    private CompletableFuture<Void> claimSequence(Player p,List<Goods> goods,int index) {
        if(index==goods.size()) return CompletableFuture.completedFuture(null);
        return plugin.service().claim(p,goods.get(index)).thenCompose(v->claimSequence(p,goods,index+1));
    }
    private void sound(Player p) { if(plugin.settings().sound()) p.playSound(p.getLocation(),Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.6f,1.2f); }
    public void wallet(Player p) {
        load(p,view->{
            Menu menu=new Menu(p,"title.wallet");
            int physical=InventoryUtil.currencyCount(InventoryUtil.snapshot(p),plugin.settings().currency(),plugin.settings().allowCurrencyMeta());
            menu.put(13,custom(plugin.settings().currency(),t.get("ui.balance"),t.get("lore.balance","balance",Money.format(view.snapshot().account().balance())),
                t.get("lore.currency","item",ItemStack.of(plugin.settings().currency()).effectiveName()),t.get("lore.physical","amount",physical),t.get("ui.wallet-help")),null);
            menu.button(20,Material.HOPPER,"ui.deposit-all",c->changed(p,plugin.service().deposit(p,InventoryUtil.currencyCount(InventoryUtil.snapshot(p),plugin.settings().currency(),plugin.settings().allowCurrencyMeta())),()->wallet(p)));
            menu.button(22,Material.CHEST,"ui.deposit",c->ask(p,"input.deposit","1",value->changed(p,plugin.service().deposit(p,Integer.parseInt(value.trim())),()->wallet(p)),()->wallet(p)));
            menu.button(24,Material.DROPPER,"ui.withdraw",c->ask(p,"input.withdraw","1",value->changed(p,plugin.service().withdraw(p,Integer.parseInt(value.trim())),()->wallet(p)),()->wallet(p)));
            menu.button(31,Material.GOLD_BLOCK,"ui.withdraw-all",c->changed(p,plugin.service().withdraw(p,Math.toIntExact(view.snapshot().account().balance()/100)),()->wallet(p)));
            if(view.snapshot().account().frozen()) menu.button(40,Material.BARRIER,"ui.frozen-account",null,"msg.frozen");
            back(menu,p,()->market(p)); menu.show(p);
        });
    }
    public void sell(Player p,LinkedHashMap<Integer,Selection> selected,Integer duration) {
        plugin.service().permission(p,"freemarket.sell"); plugin.service().requireRegion(p,null);
        Menu menu=new Menu(p,"title.sell");
        int slot=0;
        for(var entry:selected.entrySet()) {
            Selection selection=entry.getValue(); ItemStack item=selection.expected().clone(); item.setAmount(selection.amount());
            menu.put(slot++,withLore(item,List.of(t.get("lore.selected","slot",selection.slot()+1,"amount",selection.amount(),"price",Money.format(selection.price()),"hours",selection.hours()),t.get("ui.selected-help"))),click->{
                if(click.isRightClick()) { selected.remove(selection.slot()); sell(p,selected,duration); }
                else editSelection(p,selected,selection.slot(),selection.expected(),duration);
            });
        }
        menu.backpack=index->{
            ItemStack[] storage=InventoryUtil.snapshot(p);
            if(index>=0 && index<storage.length && !InventoryUtil.empty(storage[index])) editSelection(p,selected,index,storage[index].clone(),duration);
        };
        menu.button(45,Material.RED_CONCRETE,"ui.cancel",c->market(p));
        menu.button(47,Material.PAPER,"ui.uniform-price",c->{
            if(selected.isEmpty()) throw new MarketException("msg.select-items");
            ask(p,"input.price","1.00",value->{
                long price=Money.parse(value); plugin.service().validatePrice(price);
                selected.replaceAll((key,item)->new Selection(item.slot(),item.expected(),item.amount(),price,item.hours()));
                sell(p,selected,duration);
            },()->sell(p,selected,duration));
        });
        menu.put(50,custom(Material.CLOCK,t.get("ui.duration","hours",duration==null?plugin.settings().defaultHours():duration),t.get("ui.duration-help")),click->{
            List<Integer> choices=List.of(12,24,72,168).stream().filter(h->h<=plugin.settings().maxHours()).toList();
            if(choices.isEmpty()) { sell(p,selected,plugin.settings().maxHours()); return; }
            int current=duration==null?plugin.settings().defaultHours():duration;
            int next=choices.get((choices.indexOf(current)+1)%choices.size());
            selected.replaceAll((key,item)->new Selection(item.slot(),item.expected(),item.amount(),item.price(),next)); sell(p,selected,next);
        });
        menu.button(53,Material.LIME_CONCRETE,"ui.confirm-listings",click->{
            if(selected.isEmpty()) throw new MarketException("msg.select-items");
            List<Selection> copies=List.copyOf(selected.values());
            watch(p,plugin.service().sell(p,copies),v->{
                t.send(p,"msg.saved");
                if(plugin.settings().listingBroadcast()) Bukkit.broadcast(t.get("broadcast.listing","seller",p.getName()));
            },()->my(p));
        });
        menu.put(40,icon(Material.BOOK,"ui.sell-help","ui.sell-help-one","ui.sell-help-two"),null);
        menu.show(p);
    }
    private void editSelection(Player p,LinkedHashMap<Integer,Selection> selected,int slot,ItemStack expected,Integer duration) {
        Selection existing=selected.get(slot);
        ask(p,"input.quantity",String.valueOf(existing==null?Math.min(expected.getAmount(),plugin.settings().maxStack()):existing.amount()),quantity->{
            int amount=Integer.parseInt(quantity.trim());
            if(amount<1 || amount>Math.min(expected.getAmount(),plugin.settings().maxStack())) throw new MarketException("msg.number");
            ask(p,"input.price",existing==null?"1.00":Money.format(existing.price()),priceText->{
                long price=Money.parse(priceText); plugin.service().validatePrice(price);
                String category=plugin.settings().category(expected.getType());
                int hours=duration==null?plugin.settings().categoryHours().getOrDefault(category,plugin.settings().defaultHours()):duration;
                selected.put(slot,new Selection(slot,expected.clone(),amount,price,hours));
                PriceStats stats=null;
                CompletableFuture<Map<String,PriceStats>> prices=plugin.prices().all();
                if(prices.isDone()&&!prices.isCompletedExceptionally()) stats=prices.getNow(Map.of()).get(InventoryUtil.key(expected));
                if(stats!=null && stats.average()>0 && Math.abs(price/100.0-stats.average())/stats.average()*100>plugin.settings().deviation())
                    t.send(p,"msg.price-warning","average",decimal(stats.average()));
                sell(p,selected,duration);
            },()->sell(p,selected,duration));
        },()->sell(p,selected,duration));
    }
    public void owner(Player p) {
        load(p,view->{
            Snapshot s=view.snapshot(); Optional<Market> owned=s.owned(p.getUniqueId().toString());
            Menu menu=new Menu(p,"title.owner");
            if(owned.isEmpty()) {
                menu.put(22,custom(Material.GRASS_BLOCK,t.get("ui.create-market"),t.get("lore.chunk-cost","cost",Money.format(plugin.settings().chunkCost())),t.get("ui.create-help")),
                    c->buyCurrentChunk(p));
            } else {
                Market market=owned.get(); long chunks=s.chunks().stream().filter(c->c.marketId().equals(market.id())).count();
                menu.put(4,custom(Material.GRASS_BLOCK,t.get("ui.owner-info"),t.get("lore.chunks","amount",chunks),t.get("lore.tax","tax",Money.format(market.taxBps())),t.get("lore.tax-balance","amount",Money.format(market.taxBalance()))),null);
                menu.button(12,Material.REDSTONE,"ui.tax-minus",c->changed(p,plugin.service().tax(p,market,Math.max(0,market.taxBps()-(c.isShiftClick()?500:100))),()->owner(p)));
                menu.put(13,custom(Material.GOLD_INGOT,t.get("ui.tax-value","tax",Money.format(market.taxBps())),t.get("ui.tax-help")),
                    c->ask(p,"input.tax",Money.format(market.taxBps()),value->changed(p,plugin.service().tax(p,market,Math.toIntExact(Money.parse(value))),()->owner(p)),()->owner(p)));
                menu.button(14,Material.EMERALD,"ui.tax-plus",c->changed(p,plugin.service().tax(p,market,Math.min(10000,market.taxBps()+(c.isShiftClick()?500:100))),()->owner(p)));
                menu.button(29,Material.HOPPER,"ui.claim-tax",c->watch(p,plugin.service().claimTax(p,market),amount->t.send(p,"msg.tax-claimed","amount",Money.format(amount)),()->owner(p)));
                menu.button(31,Material.GRASS_BLOCK,"ui.expand-market",c->buyCurrentChunk(p));
                menu.button(33,Material.BARRIER,"ui.close-market",c->confirm(p,"title.close-market",t.get("ui.close-market-description"),()->changed(p,plugin.service().close(p,market),()->owner(p)),()->owner(p)));
                menu.button(35,Material.FILLED_MAP,"ui.chunks",c->chunks(p,0,false));
            }
            if(plugin.admin(p)) menu.button(46,Material.COMMAND_BLOCK,"ui.admin",c->admin(p));
            nearest(menu,43,p,owned.map(Market::id).orElse(null)); back(menu,p,()->market(p)); menu.show(p);
        });
    }
    private void buyCurrentChunk(Player p) {
        Location where=p.getLocation(); String world=where.getWorld().getUID().toString(); int x=where.getBlockX()>>4,z=where.getBlockZ()>>4;
        long fee=plugin.settings().chunkCost();
        confirm(p,"title.buy-chunk",t.get("ui.buy-chunk-description","world",where.getWorld().getName(),"x",x,"z",z,"cost",Money.format(fee)),()->{
            Location actual=p.getLocation();
            if(!actual.getWorld().getUID().toString().equals(world) || (actual.getBlockX()>>4)!=x || (actual.getBlockZ()>>4)!=z)
                throw new MarketException("msg.chunk-location");
            changed(p,plugin.service().buyChunk(p,fee),()->owner(p));
        },()->owner(p));
    }
    public void chunks(Player p,int page,boolean all) {
        if(all && !plugin.admin(p)) throw new MarketException("msg.not-admin");
        load(p,view->{
            Snapshot s=view.snapshot();
            List<MarketChunk> list=s.chunks().stream().filter(c->all||Objects.requireNonNull(s.market(c.marketId())).owner().equals(p.getUniqueId().toString())).toList();
            Menu menu=new Menu(p,all?"title.all-chunks":"title.chunks","page",page+1); int slot=0;
            for(MarketChunk chunk:slice(list,page,36)) {
                Market m=s.market(chunk.marketId());
                Material icon=chunk.environment().equals("NETHER")?Material.NETHERRACK:chunk.environment().equals("THE_END")?Material.END_STONE:Material.GRASS_BLOCK;
                menu.put(slot++,custom(icon,t.get("ui.chunk","owner",m.ownerName()),coordinates(chunk),t.get(all?"ui.coords-help":"ui.chunk-help")),click->{
                    if(!all && click==ClickType.SHIFT_RIGHT) confirm(p,"title.remove-chunk",coordinates(chunk),()->changed(p,plugin.service().remove(p,chunk),()->chunks(p,page,false)),()->chunks(p,page,false));
                    else sendCoordinates(p,chunk);
                });
            }
            pages(menu,page,36,list.size(),n->chunks(p,n,all)); back(menu,p,()->{if(all) admin(p);else owner(p);}); menu.show(p);
        });
    }
    public void admin(Player p) {
        if(!plugin.admin(p)) throw new MarketException("msg.not-admin");
        load(p,view->{
            if(!plugin.admin(p)) throw new MarketException("msg.not-admin");
            Menu menu=new Menu(p,"title.admin");
            menu.button(10,Material.FILLED_MAP,"ui.all-markets",c->chunks(p,0,true));
            menu.button(12,Material.COMPARATOR,"ui.rules",c->rules(p,0));
            menu.button(14,Material.PAPER,"ui.limits",c->limits(p,0));
            menu.button(16,Material.BARRIER,"ui.blacklist",c->blacklist(p,0));
            menu.button(28,Material.PLAYER_HEAD,"ui.admins",c->staff(p,0));
            menu.button(30,Material.REDSTONE_BLOCK,"ui.frozen",c->frozen(p,0));
            menu.button(32,Material.WRITABLE_BOOK,"ui.stats",c->{
                long from=System.currentTimeMillis()-86400000L;
                watch(p,plugin.db().read(db->Repository.stats(db,from)),stats->t.send(p,"msg.stats","total",Money.format(stats.total()),"tax",Money.format(stats.tax()),"volume",stats.volume(),"listings",stats.listings(),"hot",String.join(", ",stats.hot())),()->admin(p));
            });
            menu.button(49,Material.CLOCK,"ui.reload",c->changed(p,plugin.reloadFiles(),()->admin(p)));
            menu.button(53,Material.BARRIER,"ui.close",c->p.closeInventory()); menu.show(p);
        });
    }
    private void checkAdmin(Player p) { if(!plugin.admin(p)) throw new MarketException("msg.not-admin"); }
    private static final List<Rule> RULES=List.of(new Rule("market.chunk-cost",false),new Rule("market.default-tax-percent",false),
        new Rule("listing.default-duration-hours",true),new Rule("price.min-unit-price",false),new Rule("price.max-unit-price",false),
        new Rule("listing.max-duration-hours",true),new Rule("delivery.delay-seconds",true),new Rule("listing.cooldown-seconds",true),
        new Rule("listing.max-stack-amount",true),new Rule("market.max-chunks-per-owner",true));
    private void rules(Player p,int page) {
        checkAdmin(p); Menu menu=new Menu(p,"title.rules","page",page+1);
        List<Rule> rules=slice(RULES,page,5); int row=0;
        for(Rule rule:rules) {
            Object value=plugin.configValue(rule.key()); String shown=String.valueOf(value); int slot=row++*9+3;
            menu.button(slot-1,Material.REDSTONE,"ui.minus",click->adjustRule(p,rule,click.isShiftClick()?-5:-1,page));
            menu.put(slot,custom(Material.PAPER,t.get("rule."+rule.key()),t.get("lore.value","value",shown),t.get("ui.rule-help")),
                click->ask(p,"input.number",shown,text->saveRule(p,rule,text,page),()->rules(p,page)));
            menu.button(slot+1,Material.EMERALD,"ui.plus",click->adjustRule(p,rule,click.isShiftClick()?5:1,page));
        }
        pages(menu,page,5,RULES.size(),n->rules(p,n)); back(menu,p,()->admin(p)); menu.show(p);
    }
    private void adjustRule(Player p,Rule rule,int delta,int page) {
        checkAdmin(p); String current=String.valueOf(plugin.configValue(rule.key()));
        String value=rule.integer()?String.valueOf(Math.addExact(Integer.parseInt(current),delta)):Money.format(Math.addExact(Money.parse(current),delta*100L));
        saveRule(p,rule,value,page);
    }
    private void saveRule(Player p,Rule rule,String value,int page) {
        Object typed=rule.integer()?Integer.parseInt(value.trim()):Money.format(Money.parse(value));
        changed(p,plugin.configChange(p,rule.key(),typed),()->rules(p,page));
    }
    private void blacklist(Player p,int page) {
        checkAdmin(p); List<String> list=new ArrayList<>(plugin.settings().blacklist()); Collections.sort(list);
        Menu menu=new Menu(p,"title.blacklist","page",page+1); int slot=0;
        for(String name:slice(list,page,36)) {
            Material material=Material.matchMaterial(name);
            menu.put(slot++,custom(material==null?Material.BARRIER:material,t.get("ui.blacklist-material","material",name),t.get("ui.remove-help")),click->{
                checkAdmin(p);
                if(click==ClickType.SHIFT_RIGHT) {
                    Set<String> next=new TreeSet<>(plugin.settings().blacklist()); next.remove(name);
                    changed(p,plugin.configChange(p,"blacklist.materials",new ArrayList<>(next)),()->blacklist(p,page));
                }
            });
        }
        menu.button(47,Material.HOPPER,"ui.blacklist-add",click->{
            checkAdmin(p); Set<String> next=new TreeSet<>(plugin.settings().blacklist());
            for(ItemStack item:InventoryUtil.snapshot(p)) if(!InventoryUtil.empty(item)) next.add(item.getType().name());
            changed(p,plugin.configChange(p,"blacklist.materials",new ArrayList<>(next)),()->blacklist(p,0));
        });
        pages(menu,page,36,list.size(),n->blacklist(p,n)); back(menu,p,()->admin(p)); menu.show(p);
    }
    private void limits(Player p,int page) {
        checkAdmin(p); Menu menu=new Menu(p,"title.limits","page",page+1); int slot=0;
        menu.put(0,custom(Material.PAPER,t.get("ui.default-limit"),t.get("lore.value","value",plugin.settings().defaultLimit())),c->ask(p,"input.number",String.valueOf(plugin.settings().defaultLimit()),
            value->changed(p,plugin.configChange(p,"listing.max-active-listings-default",Integer.parseInt(value.trim())),()->limits(p,page)),()->limits(p,page)));
        List<Map.Entry<String,Integer>> groups=new ArrayList<>(plugin.settings().groupLimits().entrySet());
        for(var entry:slice(groups,page,35)) {
            menu.put(++slot,custom(Material.PAPER,t.get("ui.group-limit","group",entry.getKey()),t.get("lore.value","value",entry.getValue()),t.get("ui.group-help")),click->{
                String key="listing.max-active-listings-by-group."+entry.getKey();
                if(click==ClickType.SHIFT_RIGHT) changed(p,plugin.configChange(p,key,null),()->limits(p,page));
                else ask(p,"input.number",String.valueOf(entry.getValue()),value->changed(p,plugin.configChange(p,key,Integer.parseInt(value.trim())),()->limits(p,page)),()->limits(p,page));
            });
        }
        menu.button(47,Material.EMERALD,"ui.group-add",c->ask(p,"input.group","vip:20",value->{
            String[] parts=value.split(":",-1);
            if(parts.length!=2||!parts[0].matches("[a-z0-9_-]{1,32}")) throw new MarketException("msg.number");
            changed(p,plugin.configChange(p,"listing.max-active-listings-by-group."+parts[0],Integer.parseInt(parts[1])),()->limits(p,page));
        },()->limits(p,page)));
        pages(menu,page,35,groups.size(),n->limits(p,n)); back(menu,p,()->admin(p)); menu.show(p);
    }
    private void staff(Player p,int page) {
        checkAdmin(p); Menu menu=new Menu(p,"title.staff","page",page+1); int slot=0;
        List<String> names=new ArrayList<>(plugin.admins().keySet());
        for(String name:slice(names,page,36)) menu.put(slot++,custom(Material.PLAYER_HEAD,t.get("ui.staff-name","name",name),t.get("ui.console-only")),null);
        menu.button(47,Material.COMMAND_BLOCK,"ui.console-only",null,"ui.console-admin-help");
        pages(menu,page,36,names.size(),n->staff(p,n)); back(menu,p,()->admin(p)); menu.show(p);
    }
    private void frozen(Player p,int page) {
        checkAdmin(p);
        Inventory previous=p.getOpenInventory().getTopInventory();
        plugin.db().read(Repository::frozen).whenComplete((list,error)->plugin.sync(()->{
            if(!p.isOnline() || p.getOpenInventory().getTopInventory()!=previous) return;
            if(error!=null) { plugin.error(p,error); return; }
            try {
                checkAdmin(p); Menu menu=new Menu(p,"title.frozen","page",page+1); int slot=0;
                for(Operation operation:slice(list,page,36)) menu.put(slot++,custom(Material.REDSTONE_BLOCK,t.get("ui.operation","name",operation.ownerName()),
                    t.get("lore.operation-kind","kind",t.get("operation."+operation.kind())),t.get("lore.operation-status","status",t.get("status."+operation.status())),
                    t.get("lore.value","value",Money.format(operation.cents())),t.get("lore.operation-time","time",dates.format(Instant.ofEpochMilli(operation.created())))),
                    click->operation(p,operation,page));
                pages(menu,page,36,list.size(),n->frozen(p,n)); back(menu,p,()->admin(p)); menu.show(p);
            } catch(Exception e) { plugin.error(p,e); }
        }));
    }
    private void operation(Player p,Operation operation,int page) {
        checkAdmin(p); Menu menu=new Menu(p,"title.operation");
        menu.put(13,custom(Material.REDSTONE_BLOCK,t.get("ui.operation","name",operation.ownerName()),
            t.get("lore.operation-kind","kind",t.get("operation."+operation.kind())),
            t.get("lore.value","value",Money.format(operation.cents())),
            t.get("lore.operation-currency","material",operation.currency()),
            t.get("lore.operation-id","id",operation.id().substring(0,18)),
            Component.text(operation.id().substring(18)),t.get("ui.recovery-warning")),null);
        menu.button(20,Material.CHEST,"ui.inventory-before",c->inventoryPreview(p,operation.before(),()->operation(p,operation,page)));
        menu.button(24,Material.BARREL,"ui.inventory-after",c->inventoryPreview(p,operation.after(),()->operation(p,operation,page)));
        menu.button(29,Material.LIME_CONCRETE,"ui.physical-done",c->confirm(p,"title.recovery",t.get("ui.physical-done-description"),
            ()->changed(p,plugin.service().resolve(p,operation.id(),true),()->frozen(p,page)),()->operation(p,operation,page)));
        menu.button(33,Material.RED_CONCRETE,"ui.physical-not-done",c->confirm(p,"title.recovery",t.get("ui.physical-not-done-description"),
            ()->changed(p,plugin.service().resolve(p,operation.id(),false),()->frozen(p,page)),()->operation(p,operation,page)));
        back(menu,p,()->frozen(p,page)); menu.show(p);
    }
    private void inventoryPreview(Player p,byte[] bytes,Runnable returnTo) {
        checkAdmin(p); Menu menu=new Menu(p,"title.inventory-preview"); ItemStack[] items=InventoryUtil.items(bytes);
        for(int i=0;i<Math.min(36,items.length);i++) if(!InventoryUtil.empty(items[i])) menu.put(i,items[i],null);
        back(menu,p,returnTo); menu.show(p);
    }
    public boolean owns(InventoryView view,Player p) { return view.getTopInventory().getHolder(false) instanceof Menu || input.owns(view,p); }
    public void click(InventoryClickEvent event,Player p) {
        if(input.click(event,p)) return;
        if(!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
        event.setCancelled(true);
        if(!menu.owner.equals(p.getUniqueId())) return;
        int raw=event.getRawSlot();
        if(raw>=event.getView().getTopInventory().getSize()) {
            if(menu.backpack!=null && event.getClickedInventory()==p.getInventory() && event.getSlot()<36 && event.getSlot()>=0) {
                int slot=event.getSlot(); defer(p,menu.inventory,()->menu.backpack.accept(slot));
            }
            return;
        }
        ItemStack clicked=event.getCurrentItem();
        if(raw<0||InventoryUtil.empty(clicked)||!Objects.equals(clicked.getPersistentDataContainer().get(actionKey,PersistentDataType.STRING),menu.token+":"+raw)) return;
        Action action=menu.actions.get(raw); ClickType click=event.getClick();
        if(action!=null) defer(p,menu.inventory,()->{if(menu.adminOnly) checkAdmin(p);action.run(click);});
    }
    public void defer(Player p,Inventory inventory,Runnable action) {
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!p.isOnline()||p.getOpenInventory().getTopInventory()!=inventory) return;
            try { action.run(); } catch(Exception e) { plugin.error(p,e); }
        });
    }
    public void closed(InventoryCloseEvent event,Player p) {
        input.closed(event,p);
        if(!opening.contains(p.getUniqueId())) generations.merge(p.getUniqueId(),1L,Long::sum);
    }
    public void prepare(PrepareAnvilEvent event) { input.prepare(event); }
    public void quit(Player p) { input.quit(p); generations.remove(p.getUniqueId()); }
    public void shutdown() {
        for(Player p:Bukkit.getOnlinePlayers()) if(owns(p.getOpenInventory(),p)) p.closeInventory();
        input.clear(); generations.clear();
    }
}

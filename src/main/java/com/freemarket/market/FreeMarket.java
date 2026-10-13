package com.freemarket.market;

import com.freemarket.market.command.MarketCommands;
import com.freemarket.market.config.*;
import com.freemarket.market.gui.GuiManager;
import com.freemarket.market.listener.Guards;
import com.freemarket.market.market.*;
import com.freemarket.market.model.Models.*;
import com.freemarket.market.region.Regions;
import com.freemarket.market.storage.*;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.*;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class FreeMarket extends JavaPlugin {
    private final Texts text=new Texts();
    private final Regions regions=new Regions();
    private Settings settings;
    private YamlConfiguration config,categories;
    private Database database;
    private MarketService service;
    private PriceAnalyzer prices;
    private GuiManager gui;
    private Map<String,String> admins=Map.of();
    private BukkitTask expire;
    private volatile boolean closing;
    private boolean ready;
    private long revision;
    private record Loaded(YamlConfiguration config,YamlConfiguration categories,YamlConfiguration language) {}
    private record Warm(Map<String,String> admins,List<MarketChunk> chunks) {}
    @Override public void onEnable() {
        try {
            try(InputStream defaults=getResource("lang/zh_CN.yml")) {
                if(defaults!=null) text.load(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults,StandardCharsets.UTF_8)));
            }
            saveDefaultConfig();
            if(!new File(getDataFolder(),"categories.yml").exists()) saveResource("categories.yml",false);
            if(!new File(getDataFolder(),"lang/zh_CN.yml").exists()) saveResource("lang/zh_CN.yml",false);
            Loaded loaded=loadFiles(); apply(loaded);
            Path folder=getDataFolder().toPath().toAbsolutePath().normalize();
            Path file=folder.resolve(config.getString("storage.file","market.db")).normalize();
            if(!file.startsWith(folder) || file.equals(folder)) throw new IllegalArgumentException("数据库文件必须位于插件目录内");
            database=new Database(file); service=new MarketService(this); prices=new PriceAnalyzer(this); gui=new GuiManager(this);
            getServer().getPluginManager().registerEvents(new Guards(this),this);
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event->{
                event.registrar().register("market",text.plain("command.market-description"),List.of("fm","mkt","shop","市场"),new MarketCommands(this,false));
                event.registrar().register("marketadmin",text.plain("command.admin-description"),List.of("fmadmin"),new MarketCommands(this,true));
            });
            database.read(c->new Warm(Repository.admins(c),Repository.query(c,"SELECT * FROM chunks",
                r->new MarketChunk(r.getString("market_id"),r.getString("world_id"),r.getString("world_name"),r.getString("environment"),r.getInt("x"),r.getInt("z"),r.getInt("y")))))
                .whenComplete((warm,error)->sync(()->{
                    if(error!=null) { logError(error); getServer().getPluginManager().disablePlugin(this); return; }
                    admins=warm.admins(); regions.set(warm.chunks()); ready=true; scheduleExpire();
                    for(Player p:Bukkit.getOnlinePlayers()) joined(p);
                    getLogger().info(text.plain("log.enabled","version",getPluginMeta().getVersion()));
                }));
        } catch(Exception e) {
            getLogger().log(Level.SEVERE,text.plain("log.startup-failed"),e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    private Loaded loadFiles() throws Exception {
        return new Loaded(loadYaml("config.yml"),loadYaml("categories.yml"),loadYaml("lang/zh_CN.yml"));
    }
    private YamlConfiguration loadYaml(String path) throws Exception {
        YamlConfiguration yaml=new YamlConfiguration(); yaml.load(new File(getDataFolder(),path)); return yaml;
    }
    private void apply(Loaded loaded) {
        Settings checked=Settings.load(loaded.config(),loaded.categories());
        config=loaded.config(); categories=loaded.categories(); settings=checked; text.load(loaded.language()); registerLimits();
    }
    private void registerLimits() {
        for(String group:settings.groupLimits().keySet()) {
            String node="freemarket.limit."+group;
            if(getServer().getPluginManager().getPermission(node)==null) getServer().getPluginManager().addPermission(new Permission(node,PermissionDefault.FALSE));
        }
    }
    public CompletableFuture<Void> reloadFiles() {
        CompletableFuture<Void> result=new CompletableFuture<>();
        database.read(c->loadFiles()).whenComplete((loaded,error)->sync(()->{
            if(error!=null) { result.completeExceptionally(error); return; }
            try {
                if(!Objects.equals(config.getString("storage.file"),loaded.config().getString("storage.file")))
                    throw new IllegalArgumentException("数据库文件路径修改需要重启");
                apply(loaded); revision++; prices.invalidate(); scheduleExpire(); refreshAdmins(); result.complete(null);
            } catch(Exception e) { result.completeExceptionally(e); }
        }));
        return result;
    }
    public CompletableFuture<Void> configChange(Player actor,String key,Object value) {
        if(!admin(actor)) throw new MarketException("msg.not-admin");
        CompletableFuture<Void> result=new CompletableFuture<>();
        try {
            YamlConfiguration next=new YamlConfiguration(); next.loadFromString(config.saveToString()); next.set(key,value);
            Settings checked=Settings.load(next,categories); YamlConfiguration old=config; Settings previous=settings;
            config=next; settings=checked; registerLimits(); long thisRevision=++revision;
            String exported=next.saveToString(); Path target=new File(getDataFolder(),"config.yml").toPath();
            database.read(c->{
                Path temp=Files.createTempFile(target.getParent(),"config-",".tmp");
                try { Files.writeString(temp,exported); Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                finally { Files.deleteIfExists(temp); }
                return null;
            }).whenComplete((v,error)->sync(()->{
                if(error!=null) {
                    if(revision==thisRevision) { config=old; settings=previous; }
                    result.completeExceptionally(error);
                } else {
                    prices.invalidate(); scheduleExpire();
                    for(Player p:Bukkit.getOnlinePlayers()) if(admin(p)) text.send(p,"msg.admin-saved","key",key,"value",value);
                    result.complete(null);
                }
            }));
        } catch(Exception e) { result.completeExceptionally(e); }
        return result;
    }
    public Object configValue(String key) { return config.get(key); }
    public void refreshAdmins() {
        database.read(Repository::admins).whenComplete((value,error)->sync(()->{
            if(error!=null) logError(error); else admins=value;
        }));
    }
    public boolean admin(Player p) {
        String uuid=admins.get(p.getName());
        if(uuid!=null && uuid.equals(p.getUniqueId().toString())) return true;
        if(admins.containsKey(p.getName()) && uuid==null) return true;
        return admins.values().stream().anyMatch(p.getUniqueId().toString()::equals);
    }
    public Map<String,String> admins() { return Collections.unmodifiableMap(admins); }
    public void installAdmins(Map<String,String> value) { admins=Collections.unmodifiableMap(new LinkedHashMap<>(value)); }
    public void joined(Player p) {
        if(!ready) return;
        service.snapshot(p).whenComplete((snapshot,error)->sync(()->{
            if(error!=null) { logError(error); return; }
            regions.set(snapshot.chunks()); refreshAdmins();
            if(p.isOnline()) {
                if(!snapshot.goods().isEmpty()) text.send(p,"msg.join-goods","count",snapshot.goods().size());
                if(snapshot.account().frozen()) text.send(p,"msg.frozen");
            }
        }));
    }
    private void scheduleExpire() {
        if(expire!=null) expire.cancel();
        expire=Bukkit.getScheduler().runTaskTimer(this,()->{
            long now=System.currentTimeMillis(),retain=now-settings.keepDays()*86400000L;
            database.transaction(c->Repository.expire(c,now,retain)).whenComplete((counts,error)->sync(()->{
                if(error!=null) { logError(error); return; }
                if(!counts.isEmpty()) prices.invalidate();
                for(var entry:counts.entrySet()) {
                    Player p=Bukkit.getPlayer(UUID.fromString(entry.getKey()));
                    if(p!=null) text.send(p,"msg.expired","count",entry.getValue());
                }
            }));
        },20,settings.scanSeconds()*20L);
    }
    public void sync(Runnable work) {
        if(closing) return;
        if(Bukkit.isPrimaryThread()) work.run(); else Bukkit.getScheduler().runTask(this,()->{ if(!closing) work.run(); });
    }
    public void error(CommandSender target,Throwable error) {
        Throwable root=error; while(root.getCause()!=null && (root instanceof CompletionException || root instanceof ExecutionException)) root=root.getCause();
        if(root instanceof MarketException || root instanceof IllegalArgumentException) {
            String key=root.getMessage(); text.send(target,text.has(key==null?"":key)?key:"msg.number");
        } else { logError(root); text.send(target,"msg.error"); }
    }
    public void logError(Throwable error) { getLogger().log(Level.WARNING,text.plain("log.error"),error); }
    @Override public void onDisable() {
        closing=true; ready=false;
        if(expire!=null) expire.cancel();
        if(gui!=null) gui.shutdown();
        if(database!=null) database.close();
    }
    public boolean ready() { return ready; }
    public Settings settings() { return settings; }
    public Texts text() { return text; }
    public Regions regions() { return regions; }
    public Database db() { return database; }
    public MarketService service() { return service; }
    public PriceAnalyzer prices() { return prices; }
    public GuiManager gui() { return gui; }
}

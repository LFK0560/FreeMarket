package com.freemarket.market.command;

import com.freemarket.market.FreeMarket;
import com.freemarket.market.storage.*;
import com.freemarket.market.util.Money;
import io.papermc.paper.command.brigadier.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

/** 使用 Paper 命令生命周期与 BasicCommand，避免旧式命令执行器 API。 */
public final class MarketCommands implements BasicCommand {
    private final FreeMarket plugin;
    private final boolean management;
    public MarketCommands(FreeMarket plugin,boolean management) { this.plugin=plugin; this.management=management; }
    @Override public void execute(CommandSourceStack source,String[] args) {
        CommandSender sender=source.getSender();
        try {
            if(!plugin.ready()) { plugin.text().send(sender,"msg.loading"); return; }
            if(management) { manage(sender,args); return; }
            if(!(sender instanceof Player p)) { plugin.text().send(sender,"command.player-only"); return; }
            String sub=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
            switch(sub) {
                case ""->plugin.gui().market(p);
                case "sell"->plugin.gui().sell(p,new LinkedHashMap<>(),null);
                case "my"->plugin.gui().my(p);
                case "pending"->plugin.gui().pending(p,0);
                case "admin"->plugin.gui().admin(p);
                case "reload"->{
                    if(!plugin.admin(p)) throw new MarketException("msg.not-admin");
                    plugin.reloadFiles().whenComplete((v,e)->plugin.sync(()->{if(e!=null) plugin.error(p,e);else plugin.text().send(p,"msg.reloaded");}));
                }
                default->plugin.text().send(sender,"command.market-usage");
            }
        } catch(Exception e) { plugin.error(sender,e); }
    }
    private boolean console(CommandSender sender) { return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender; }
    private void manage(CommandSender sender,String[] args) {
        String sub=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("setadmin")||sub.equals("removeadmin")) {
            if(!console(sender)) throw new MarketException("msg.console-only");
            if(args.length!=2) { plugin.text().send(sender,"command.admin-usage"); return; }
            String name=args[1]; boolean add=sub.equals("setadmin");
            plugin.db().transaction(c->{Repository.setAdmin(c,name,add);return Repository.admins(c);}).whenComplete((admins,error)->plugin.sync(()->{
                if(error!=null) { plugin.error(sender,error); return; }
                plugin.installAdmins(admins);
                plugin.text().send(sender,add?"msg.admin-added":"msg.admin-removed","name",name);
            }));
            return;
        }
        if(!console(sender) && (!(sender instanceof Player p)||!plugin.admin(p))) throw new MarketException("msg.not-admin");
        switch(sub) {
            case "listadmin"->plugin.text().send(sender,"msg.admin-list","names",String.join(", ",plugin.admins().keySet()));
            case "reload"->plugin.reloadFiles().whenComplete((v,e)->plugin.sync(()->{if(e!=null) plugin.error(sender,e);else plugin.text().send(sender,"msg.reloaded");}));
            case "stats"->{
                long from=System.currentTimeMillis()-86400000L;
                plugin.db().read(c->Repository.stats(c,from)).whenComplete((stats,error)->plugin.sync(()->{
                    if(error!=null) plugin.error(sender,error);
                    else plugin.text().send(sender,"msg.stats","total",Money.format(stats.total()),"tax",Money.format(stats.tax()),"volume",stats.volume(),"listings",stats.listings(),"hot",String.join(", ",stats.hot()));
                }));
            }
            case ""->{if(sender instanceof Player p) plugin.gui().admin(p);else plugin.text().send(sender,"command.admin-usage");}
            default->plugin.text().send(sender,"command.admin-usage");
        }
    }
    @Override public Collection<String> suggest(CommandSourceStack source,String[] args) {
        List<String> candidates=new ArrayList<>();
        CommandSender sender=source.getSender();
        if(args.length<=1) {
            if(management) {
                if(console(sender)) candidates.addAll(List.of("setadmin","removeadmin","listadmin","reload","stats"));
                else if(sender instanceof Player p && plugin.admin(p)) candidates.addAll(List.of("listadmin","reload","stats"));
            } else {
                candidates.addAll(List.of("sell","my","pending"));
                if(sender instanceof Player p&&plugin.admin(p)) candidates.addAll(List.of("admin","reload"));
            }
        } else if(management&&console(sender)&&args.length==2) {
            if(args[0].equalsIgnoreCase("setadmin")) for(Player p:Bukkit.getOnlinePlayers()) candidates.add(p.getName());
            if(args[0].equalsIgnoreCase("removeadmin")) candidates.addAll(plugin.admins().keySet());
        }
        String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().toList();
    }
}

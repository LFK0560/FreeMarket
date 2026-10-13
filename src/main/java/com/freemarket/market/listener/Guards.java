package com.freemarket.market.listener;

import com.freemarket.market.FreeMarket;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;

/** 实物事务等待数据库时禁止会改变背包的操作；菜单物品永远无法转移。 */
public final class Guards implements Listener {
    private final FreeMarket plugin;
    public Guards(FreeMarket plugin) { this.plugin=plugin; }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void click(InventoryClickEvent event) {
        if(!(event.getWhoClicked() instanceof Player p)) return;
        if(plugin.service().busy(p)) { event.setCancelled(true); return; }
        plugin.gui().click(event,p);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void drag(InventoryDragEvent event) {
        if(event.getWhoClicked() instanceof Player p && (plugin.service().busy(p)||plugin.gui().owns(event.getView(),p))) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if(event.getPlayer() instanceof Player p) plugin.gui().closed(event,p);
    }
    @EventHandler public void prepare(PrepareAnvilEvent event) { plugin.gui().prepare(event); }
    @EventHandler public void join(PlayerJoinEvent event) { plugin.joined(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) { plugin.gui().quit(event.getPlayer()); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void drop(PlayerDropItemEvent event) { if(plugin.service().busy(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void pickup(EntityPickupItemEvent event) {
        if(event.getEntity() instanceof Player p&&plugin.service().busy(p)) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void swap(PlayerSwapHandItemsEvent event) { if(plugin.service().busy(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interact(PlayerInteractEvent event) { if(plugin.service().busy(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interactEntity(PlayerInteractEntityEvent event) { if(plugin.service().busy(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void consume(PlayerItemConsumeEvent event) { if(plugin.service().busy(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damageItem(PlayerItemDamageEvent event) { if(plugin.service().busy(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        if(event.getEntity() instanceof Player p&&plugin.service().busy(p)) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent event) { if(plugin.service().busy(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void move(PlayerMoveEvent event) {
        Player p=event.getPlayer(); if(!plugin.service().busy(p)||event.getTo()==null) return;
        if((event.getFrom().getBlockX()>>4)!=(event.getTo().getBlockX()>>4)||(event.getFrom().getBlockZ()>>4)!=(event.getTo().getBlockZ()>>4))
            event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void command(PlayerCommandPreprocessEvent event) {
        if(plugin.service().busy(event.getPlayer())) { event.setCancelled(true); plugin.text().send(event.getPlayer(),"msg.busy"); }
    }
}

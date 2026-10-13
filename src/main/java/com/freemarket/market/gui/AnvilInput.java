package com.freemarket.market.gui;

import com.freemarket.market.FreeMarket;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.function.*;

/** 使用 Paper 类型化铁砧视图，输入与结果都是无法取出的展示物品。 */
public final class AnvilInput {
    private record Session(Inventory inventory,AnvilView view,Consumer<String> submit,Runnable cancel,String token) {}
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final FreeMarket plugin;
    private final GuiManager gui;
    public AnvilInput(FreeMarket plugin,GuiManager gui) { this.plugin=plugin; this.gui=gui; }
    public void open(Player p,String title,String initial,Consumer<String> submit,Runnable cancel) {
        Session previous=sessions.remove(p.getUniqueId());
        if(previous!=null) previous.inventory().clear();
        AnvilView view=MenuType.ANVIL.builder().checkReachable(false).title(plugin.text().get(title)).build(p);
        Session session=new Session(view.getTopInventory(),view,submit,cancel,UUID.randomUUID().toString());
        gui.openGuard(p,()->p.openInventory(view));
        sessions.put(p.getUniqueId(),session);
        ItemStack item=ItemStack.of(Material.PAPER); item.editMeta(meta->meta.displayName(net.kyori.adventure.text.Component.text(initial)));
        item.editPersistentDataContainer(data->data.set(new NamespacedKey(plugin,"gui_action"),PersistentDataType.STRING,session.token()));
        view.getTopInventory().setItem(0,item); view.setRepairCost(0); view.setRepairItemCountCost(0);
    }
    public boolean owns(InventoryView view,Player p) {
        Session s=sessions.get(p.getUniqueId()); return s!=null&&s.inventory()==view.getTopInventory();
    }
    public void prepare(PrepareAnvilEvent event) {
        if(!(event.getView().getPlayer() instanceof Player p)) return;
        Session s=sessions.get(p.getUniqueId());
        if(s==null||s.inventory()!=event.getInventory()) return;
        ItemStack output=ItemStack.of(Material.PAPER);
        output.editMeta(meta->{meta.displayName(plugin.text().get("ui.apply-input"));meta.lore(List.of(plugin.text().get("ui.input-help")));});
        output.editPersistentDataContainer(data->data.set(new NamespacedKey(plugin,"gui_action"),PersistentDataType.STRING,s.token()));
        event.setResult(output); s.view().setRepairCost(0); s.view().setRepairItemCountCost(0);
    }
    public boolean click(InventoryClickEvent event,Player p) {
        Session session=sessions.get(p.getUniqueId());
        if(session==null||session.inventory()!=event.getView().getTopInventory()) return false;
        event.setCancelled(true);
        if(event.getRawSlot()==2) {
            ItemStack current=event.getCurrentItem();
            if(current==null || !Objects.equals(current.getPersistentDataContainer().get(new NamespacedKey(plugin,"gui_action"),PersistentDataType.STRING),session.token())) return true;
            String value=session.view().getRenameText(); if(value==null) value="";
            String captured=value;
            gui.defer(p,session.inventory(),()->{
                if(sessions.get(p.getUniqueId())!=session) return;
                sessions.remove(p.getUniqueId()); session.inventory().clear();
                try { session.submit().accept(captured); }
                catch(Exception e) { plugin.error(p,e); session.cancel().run(); }
            });
        }
        return true;
    }
    public void closed(InventoryCloseEvent event,Player p) {
        Session session=sessions.get(p.getUniqueId());
        if(session!=null&&session.inventory()==event.getInventory()) { session.inventory().clear(); sessions.remove(p.getUniqueId()); }
    }
    public void quit(Player p) { Session s=sessions.remove(p.getUniqueId()); if(s!=null) s.inventory().clear(); }
    public void clear() { for(Session s:sessions.values()) s.inventory().clear(); sessions.clear(); }
}

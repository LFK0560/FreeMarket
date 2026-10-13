package com.freemarket.market.util;

import com.freemarket.market.storage.MarketException;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.security.*;
import java.util.*;

/** 库存快照与模拟都在主线程完成，数据库只接收字节。 */
public final class InventoryUtil {
    private InventoryUtil() {}
    public static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("物品操作必须在主线程"); }
    public static ItemStack[] copy(ItemStack[] source) {
        main(); ItemStack[] result=new ItemStack[source.length];
        for(int i=0;i<source.length;i++) result[i]=source[i]==null?null:source[i].clone(); return result;
    }
    public static ItemStack[] snapshot(Player p) { main(); return copy(p.getInventory().getStorageContents()); }
    public static byte[] bytes(ItemStack[] items) { main(); return ItemStack.serializeItemsAsBytes(Arrays.asList(items)); }
    public static ItemStack[] items(byte[] bytes) { main(); return ItemStack.deserializeItemsFromBytes(bytes); }
    public static byte[] unitBytes(ItemStack item) { main(); ItemStack copy=item.clone(); copy.setAmount(1); return copy.serializeAsBytes(); }
    public static String key(ItemStack item) {
        main(); try {
            return "minecraft:"+item.getType().name().toLowerCase(Locale.ROOT)+"@"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(unitBytes(item)));
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException("缺少物品摘要算法",e); }
    }
    public static boolean equal(ItemStack[] a,ItemStack[] b) {
        main(); if(a.length!=b.length) return false;
        for(int i=0;i<a.length;i++) {
            boolean ae=empty(a[i]),be=empty(b[i]); if(ae!=be) return false;
            if(!ae && (a[i].getAmount()!=b[i].getAmount() || !a[i].isSimilar(b[i]))) return false;
        }
        return true;
    }
    public static boolean empty(ItemStack item) { return item==null || item.isEmpty(); }
    public static ItemStack[] give(ItemStack[] storage,ItemStack item,int amount) {
        main(); ItemStack[] result=copy(storage); int left=amount,max=item.getMaxStackSize();
        for(int i=0;i<result.length && left>0;i++) if(!empty(result[i]) && result[i].isSimilar(item)) {
            int add=Math.min(left,Math.max(0,max-result[i].getAmount())); result[i].setAmount(result[i].getAmount()+add); left-=add;
        }
        for(int i=0;i<result.length && left>0;i++) if(empty(result[i])) {
            int add=Math.min(left,max); result[i]=item.clone(); result[i].setAmount(add); left-=add;
        }
        if(left>0) throw new MarketException("msg.space"); return result;
    }
    public static int currencyCount(ItemStack[] storage,Material currency,boolean allowMeta) {
        main(); int count=0; for(ItemStack i:storage) if(matches(i,currency,allowMeta)) count+=i.getAmount(); return count;
    }
    public static boolean matches(ItemStack item,Material material,boolean allowMeta) {
        main(); return !empty(item) && item.getType()==material && (allowMeta || !item.hasItemMeta());
    }
    public static ItemStack[] takeCurrency(ItemStack[] storage,Material currency,boolean allowMeta,int amount) {
        main(); ItemStack[] result=copy(storage); int left=amount;
        for(int i=0;i<result.length && left>0;i++) if(matches(result[i],currency,allowMeta)) {
            int take=Math.min(left,result[i].getAmount()); result[i].setAmount(result[i].getAmount()-take); left-=take;
        }
        if(left>0) throw new MarketException("msg.insufficient-items"); return result;
    }
}

package com.freemarket.market.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;

/** 玩家输入通过组件占位符插入，不能注入 MiniMessage 标签。 */
public final class Texts {
    private final MiniMessage mini=MiniMessage.miniMessage();
    private Map<String,String> strings=Map.of();
    public void load(YamlConfiguration yaml) {
        Map<String,String> map=new HashMap<>();
        for (String key:yaml.getKeys(true)) if (yaml.isString(key)) map.put(key,yaml.getString(key));
        strings=Map.copyOf(map);
    }
    public Component get(String key,Object... pairs) {
        String raw=strings.getOrDefault(key,key);
        TagResolver.Builder resolver=TagResolver.builder();
        for (int i=0;i<pairs.length;i+=2) {
            String name=String.valueOf(pairs[i]);
            raw=raw.replace("{"+name+"}","<p_"+name+">");
            Object value=pairs[i+1];
            resolver.resolver(Placeholder.component("p_"+name,value instanceof Component component?component:Component.text(String.valueOf(value))));
        }
        return mini.deserialize(raw,resolver.build()).decoration(TextDecoration.ITALIC,false);
    }
    public String plain(String key,Object... pairs) { return PlainTextComponentSerializer.plainText().serialize(get(key,pairs)); }
    public void send(CommandSender target,String key,Object... pairs) { target.sendMessage(get("prefix").append(get(key,pairs))); }
    public boolean has(String key) { return strings.containsKey(key); }
}

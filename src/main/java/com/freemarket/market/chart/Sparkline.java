package com.freemarket.market.chart;

import com.freemarket.market.model.Models.PricePoint;
import java.util.*;

/** 保留首尾和极值，其他点按成交量聚合；原提示词同时保峰与桶均价的冲突在此明确处理。 */
public final class Sparkline {
    private Sparkline() {}
    public record Result(String line,double change,List<PricePoint> points) {}
    public static Result render(List<PricePoint> source,int limit,String blocks) {
        if(source.isEmpty()) return new Result("",0,List.of());
        List<PricePoint> points=compress(source,limit);
        double min=points.stream().mapToDouble(PricePoint::price).min().orElse(0);
        double max=points.stream().mapToDouble(PricePoint::price).max().orElse(0);
        int[] symbols=blocks.codePoints().toArray(); StringBuilder out=new StringBuilder();
        for(PricePoint p:points) {
            int level=max-min<1e-9?symbols.length/2:(int)Math.round((p.price()-min)/(max-min)*(symbols.length-1));
            out.appendCodePoint(symbols[Math.max(0,Math.min(level,symbols.length-1))]);
        }
        double first=source.getFirst().price(),last=source.getLast().price();
        return new Result(out.toString(),first==0?0:(last-first)/first*100,List.copyOf(points));
    }
    public static List<PricePoint> compress(List<PricePoint> source,int limit) {
        if(limit<2) throw new IllegalArgumentException("至少保留两个点");
        if(source.size()<=limit) return List.copyOf(source);
        TreeSet<Integer> kept=new TreeSet<>(); kept.add(0); kept.add(source.size()-1);
        if(limit>=4) {
            int min=0,max=0;
            for(int i=1;i<source.size();i++) { if(source.get(i).price()<source.get(min).price()) min=i; if(source.get(i).price()>source.get(max).price()) max=i; }
            kept.add(min); kept.add(max);
        }
        int buckets=limit-kept.size(); Map<Integer,PricePoint> values=new TreeMap<>();
        for(int i:kept) values.put(i,source.get(i));
        List<Integer> remaining=new ArrayList<>();
        for(int i=1;i<source.size()-1;i++) if(!kept.contains(i)) remaining.add(i);
        for(int b=0;b<buckets;b++) {
            int start=b*remaining.size()/buckets,end=(b+1)*remaining.size()/buckets;
            if(start==end) continue;
            double sum=0; long volume=0;
            for(int j=start;j<end;j++) { PricePoint p=source.get(remaining.get(j)); sum+=p.price()*p.volume(); volume+=p.volume(); }
            int index=remaining.get((start+end-1)/2);
            values.put(index,new PricePoint(source.get(index).time(),volume==0?source.get(index).price():sum/volume,volume));
        }
        return List.copyOf(values.values());
    }
}

package com.freemarket.market;

import com.freemarket.market.chart.Sparkline;
import com.freemarket.market.model.Models.PricePoint;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SparklineTest {
    @Test void flatSeriesAndZeroStartAreFinite() {
        var result=Sparkline.render(List.of(new PricePoint(0,0,1),new PricePoint(1,0,2)),24,"▁▂▃▄▅▆▇█");
        assertEquals("▅▅",result.line());assertEquals(0,result.change());
    }
    @Test void compressionKeepsEndpointsAndExtremes() {
        List<PricePoint> data=new ArrayList<>();
        for(int i=0;i<100;i++) data.add(new PricePoint(i,i==31?1000:i==61?-10:10+i/10.0,i+1));
        var compressed=Sparkline.compress(data,24);
        assertTrue(compressed.size()<=24);assertEquals(data.getFirst(),compressed.getFirst());assertEquals(data.getLast(),compressed.getLast());
        assertTrue(compressed.contains(data.get(31)));assertTrue(compressed.contains(data.get(61)));
        assertEquals(24,Sparkline.render(data,24,"▁▂▃▄▅▆▇█").line().codePointCount(0,24));
    }
    @Test void downwardChangeHasCorrectSign() {
        var r=Sparkline.render(List.of(new PricePoint(0,10,1),new PricePoint(1,5,2)),24,"▁▂▃▄▅▆▇█");
        assertEquals(-50,r.change());assertEquals("█▁",r.line());
    }
}

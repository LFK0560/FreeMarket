package com.freemarket.market.storage;

/** 业务错误使用语言键，不把技术异常原文发送给玩家。 */
public final class MarketException extends RuntimeException {
    public MarketException(String key) { super(key); }
}

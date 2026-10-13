package com.freemarket.market.storage;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;

/** 长连接只属于一个串行工作线程，主线程从不执行 SQL。 */
public final class Database implements AutoCloseable {
    @FunctionalInterface public interface Work<T> { T run(Connection c) throws Exception; }
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FreeMarket-数据库"); t.setDaemon(true); return t;
    });
    private Connection connection;
    private final CompletableFuture<Void> ready;
    public Database(Path file) {
        ready = CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                try (Statement s = connection.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=FULL");
                    s.execute("PRAGMA foreign_keys=ON");
                    s.execute("PRAGMA busy_timeout=5000");
                    for (String sql : SCHEMA) s.execute(sql);
                    s.executeUpdate("UPDATE operations SET status='FROZEN', note='恢复：上次运行未确认实物操作' WHERE status='PREPARED'");
                }
            } catch (Exception e) { throw new CompletionException(e); }
        }, executor);
    }
    public CompletableFuture<Void> ready() { return ready; }
    public <T> CompletableFuture<T> read(Work<T> work) {
        return ready.thenCompose(v -> CompletableFuture.supplyAsync(() -> {
            try { return work.run(connection); } catch (Exception e) { throw new CompletionException(e); }
        }, executor));
    }
    public <T> CompletableFuture<T> transaction(Work<T> work) {
        return read(c -> {
            c.setAutoCommit(false);
            try { T result = work.run(c); c.commit(); return result; }
            catch (Exception e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        });
    }
    @Override public void close() {
        try {
            executor.submit(() -> { if (connection != null) try { connection.close(); } catch (SQLException ignored) {} });
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL DEFAULT 0, reserved_credit INTEGER NOT NULL DEFAULT 0, CHECK(balance>=0 AND reserved_credit>=0 AND balance+reserved_credit<=1000000000000000))",
        "CREATE TABLE IF NOT EXISTS markets(id TEXT PRIMARY KEY, owner TEXT NOT NULL, owner_name TEXT NOT NULL, tax_bps INTEGER NOT NULL CHECK(tax_bps BETWEEN 0 AND 10000), tax_balance INTEGER NOT NULL DEFAULT 0 CHECK(tax_balance>=0 AND tax_balance<=1000000000000000), status TEXT NOT NULL DEFAULT 'ACTIVE')",
        "CREATE UNIQUE INDEX IF NOT EXISTS one_market_per_owner ON markets(owner) WHERE status='ACTIVE'",
        "CREATE TABLE IF NOT EXISTS chunks(market_id TEXT NOT NULL REFERENCES markets(id), world_id TEXT NOT NULL, world_name TEXT NOT NULL, environment TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL, y INTEGER NOT NULL, PRIMARY KEY(world_id,x,z))",
        "CREATE TABLE IF NOT EXISTS listings(id INTEGER PRIMARY KEY AUTOINCREMENT, seller TEXT NOT NULL, seller_name TEXT NOT NULL, market_id TEXT NOT NULL REFERENCES markets(id), item_key TEXT NOT NULL, category TEXT NOT NULL, search TEXT NOT NULL, item BLOB NOT NULL, original INTEGER NOT NULL CHECK(original>0), remaining INTEGER NOT NULL CHECK(remaining>=0), sold INTEGER NOT NULL DEFAULT 0, income INTEGER NOT NULL DEFAULT 0 CHECK(income>=0), price INTEGER NOT NULL CHECK(price>0), tax_bps INTEGER NOT NULL CHECK(tax_bps BETWEEN 0 AND 10000), created INTEGER NOT NULL, expires INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE')",
        "CREATE INDEX IF NOT EXISTS listing_active ON listings(status,seller,expires)",
        "CREATE INDEX IF NOT EXISTS listing_key ON listings(item_key)",
        "CREATE TABLE IF NOT EXISTS goods(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, market_id TEXT NOT NULL REFERENCES markets(id), item BLOB NOT NULL, amount INTEGER NOT NULL CHECK(amount>0), ready_at INTEGER NOT NULL, reason TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'AVAILABLE')",
        "CREATE INDEX IF NOT EXISTS goods_owner ON goods(owner,status)",
        "CREATE TABLE IF NOT EXISTS trades(id INTEGER PRIMARY KEY AUTOINCREMENT, listing_id INTEGER NOT NULL, market_id TEXT NOT NULL, item_key TEXT NOT NULL, buyer TEXT NOT NULL, seller TEXT NOT NULL, amount INTEGER NOT NULL, unit_price INTEGER NOT NULL, total INTEGER NOT NULL, tax INTEGER NOT NULL, time INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS trade_key_time ON trades(item_key,time)",
        "CREATE TABLE IF NOT EXISTS favorites(owner TEXT NOT NULL, item_key TEXT NOT NULL, PRIMARY KEY(owner,item_key))",
        "CREATE TABLE IF NOT EXISTS admins(name TEXT PRIMARY KEY, uuid TEXT UNIQUE)",
        "CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY, owner TEXT NOT NULL, owner_name TEXT NOT NULL, market_id TEXT REFERENCES markets(id), kind TEXT NOT NULL, status TEXT NOT NULL, cents INTEGER NOT NULL, currency TEXT NOT NULL, goods_id INTEGER REFERENCES goods(id), inventory_before BLOB NOT NULL, inventory_after BLOB NOT NULL, created INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')",
        "CREATE UNIQUE INDEX IF NOT EXISTS one_physical_operation ON operations(owner) WHERE status IN ('PREPARED','FROZEN')",
        "CREATE TABLE IF NOT EXISTS operation_items(operation_id TEXT NOT NULL REFERENCES operations(id), item BLOB NOT NULL, item_key TEXT NOT NULL, category TEXT NOT NULL, search TEXT NOT NULL, amount INTEGER NOT NULL, price INTEGER NOT NULL, tax_bps INTEGER NOT NULL, duration INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, operation_id TEXT NOT NULL, decision TEXT NOT NULL, time INTEGER NOT NULL)",
        "PRAGMA user_version=1"
    };
}

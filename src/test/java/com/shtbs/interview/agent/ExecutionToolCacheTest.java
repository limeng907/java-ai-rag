package com.shtbs.interview.agent;

import com.shtbs.interview.agent.runtime.ExecutionToolCache;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionToolCacheTest {
    @Test void equivalentArgumentObjectsFetchOnlyOnce() {
        var cache=new ExecutionToolCache();var calls=new AtomicInteger();
        assertEquals("[]",cache.read("searchQuestions","{\"limit\":3,\"keyword\":\"HashMap\"}",()->{calls.incrementAndGet();return "[]";}));
        assertEquals("[]",cache.read("searchQuestions","{ \"keyword\": \"HashMap\", \"limit\": 3 }",()->{calls.incrementAndGet();return "bad";}));
        assertEquals(1,calls.get());assertEquals(1,cache.hits());assertEquals(1,cache.misses());
    }
    @Test void differentQueriesAndExecutionsDoNotShareResults() {
        var calls=new AtomicInteger();
        for (int i=0;i<2;i++) {
            var cache=new ExecutionToolCache();
            cache.read("searchKnowledge","{\"query\":\"HashMap\"}",()->"v"+calls.incrementAndGet());
            cache.read("searchKnowledge","{\"query\":\"Redis\"}",()->"v"+calls.incrementAndGet());
        }
        assertEquals(4,calls.get());
    }
    @Test void failuresAndUnapprovedToolsAreNeverCached() {
        var cache=new ExecutionToolCache();
        assertThrows(IllegalStateException.class,()->cache.read("searchKnowledge","{}",()->{throw new IllegalStateException();}));
        assertEquals("ok",cache.read("searchKnowledge","{}",()->"ok"));assertEquals(2,cache.misses());
        assertEquals("first",cache.read("writeSomething","{}",()->"first"));
        assertEquals("second",cache.read("writeSomething","{}",()->"second"));
    }
    @Test void timeoutDiagnosticsDoNotWaitForAnUninterruptibleFetch() throws Exception {
        var cache=new ExecutionToolCache();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var fetch=executor.submit(()->cache.read("searchKnowledge","{}",()->{
                entered.countDown();try { release.await(); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }return "[]";
            }));
            try {
                assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1,executor.submit(cache::misses).get(2,java.util.concurrent.TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertEquals("[]",fetch.get(2,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}

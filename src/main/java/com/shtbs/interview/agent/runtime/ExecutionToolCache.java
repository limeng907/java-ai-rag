package com.shtbs.interview.agent.runtime;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Created inside one run: identity, selected bases and lifetime never cross executions. */
public final class ExecutionToolCache {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> READS=Set.of("searchKnowledge","searchQuestions");
    private final Map<String,String> results=new ConcurrentHashMap<>();
    private final AtomicInteger hits=new AtomicInteger(),misses=new AtomicInteger();
    public String read(String tool,String input,Supplier<String> fetch) {
        if (!READS.contains(tool)) { return fetch.get(); }
        String key=tool+":"+canonical(input);
        String cached=results.get(key);
        if (cached!=null) { hits.incrementAndGet();return cached; }
        misses.incrementAndGet();
        // Never hold a monitor across provider I/O: a provider may ignore interruption.
        String output=fetch.get();
        if (output!=null && output.length()<=24000) { results.put(key,output); }
        return output;
    }
    private static String canonical(String input) {
        try {
            JsonNode node=JSON.readTree(input);
            if (node==null || !node.isObject()) { throw new IllegalArgumentException("Expected tool argument object"); }
            Map<String,JsonNode> fields=new TreeMap<>();node.properties().forEach(e->fields.put(e.getKey(),e.getValue()));
            return JSON.writeValueAsString(fields);
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid tool arguments",ex); }
    }
    public int hits() { return hits.get(); }
    public int misses() { return misses.get(); }
}

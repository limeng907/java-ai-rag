package com.shtbs.interview.agent.memory;

import java.util.*;
import java.util.regex.Pattern;

/** Deliberately narrow, deterministic recall of explicit user statements. No model judgments. */
public final class MemoryFacts {
    public enum Field { DAILY_MINUTES, INTERVIEW_COMPANY }
    public enum Status { FOUND, NOT_FOUND, CONFLICT }
    public record Source(long messageId,String quote) {}
    public record Result(Field field,Status status,String value,List<Source> sources,boolean lookupLimited) {
        public Result { sources=List.copyOf(sources); }
        public String message() {
            return switch (status) {
                case FOUND -> "根据你在本会话中的明确陈述，"+(field==Field.DAILY_MINUTES?"每天学习时间为"+value+"分钟。":"曾面试的公司是"+value+"。");
                case CONFLICT -> "本会话中的相关陈述存在冲突或无法明确解析，暂时无法确认，请重新说明。";
                case NOT_FOUND -> lookupLimited?"历史记录超过本次核对范围，暂时无法确认。":"本会话中没有找到可核对的明确陈述，暂时无法确认。";
            };
        }
    }
    private static final Pattern MINUTES=Pattern.compile("^(?:我)?(?:现在|目前|以后|改为|改成)?(?:我)?每天(?:只有|只能|可以|可|能)?(?:学习)?\\s*([0-9]{1,3})\\s*分钟(?:用于学习|用来学习|学习)?[。.!！]?$" );
    private static final Pattern COMPANY=Pattern.compile("^我(?:之前|曾经|以前)?(?:面试过|面试了)([\\p{L}\\p{N}· .&-]{1,60}?)(?:公司)?[。.!！]?$" );
    private MemoryFacts() {}
    public static Field question(String message) {
        String q=message.strip().replace("？","").replace("?","");
        if (Set.of("我之前说每天能学习多少分钟","我每天能学习多少分钟","我每天有多少学习时间").contains(q)) { return Field.DAILY_MINUTES; }
        if (Set.of("我之前面试的是哪家公司","我之前面试过哪家公司").contains(q)) { return Field.INTERVIEW_COMPANY; }
        return null;
    }
    public static String acknowledgement(String message) {
        if (message==null || message.length()>120 || question(message)!=null) { return null; }
        var row=new MemoryRepository.Row(0,UUID.randomUUID(),"USER",message);
        var minutes=resolve(Field.DAILY_MINUTES,List.of(row),false);
        if(minutes.status()==Status.FOUND) { return "已记录你在本会话中说每天可学习"+minutes.value()+"分钟。"; }
        var company=resolve(Field.INTERVIEW_COMPANY,List.of(row),false);
        if(company.status()==Status.FOUND) { return "已记录你在本会话中说曾面试"+company.value()+"公司。"; }
        return null;
    }
    public static Result resolve(Field field,List<MemoryRepository.Row> rows,boolean limited) {
        if (limited) { return new Result(field,Status.NOT_FOUND,null,List.of(),true); }
        Set<String> values=new LinkedHashSet<>();List<Source> sources=new ArrayList<>();boolean uncertain=false;
        for (var row:rows.stream().sorted(Comparator.comparingLong(MemoryRepository.Row::id)).toList()) {
            if (!"USER".equals(row.role()) || row.content()==null) { continue; }
            String text=row.content().strip();
            if (question(text)!=null) { continue; }
            // Questions, quotations and hypothetical descriptions cannot establish a user fact.
            if (text.contains("?") || text.contains("？") || text.contains("假设") || text.contains("如果") || text.contains("比如") || text.contains("\"") || text.contains("“")) { continue; }
            boolean relevant=field==Field.DAILY_MINUTES?text.matches("^(?:我)?(?:现在|目前|以后|改为|改成)?(?:我)?每天.*(?:学习|分钟).*$"):
                    text.matches("^我(?:之前|曾经|以前)?(?:面试过|面试了).*$");
            if (!relevant) { continue; }
            var match=(field==Field.DAILY_MINUTES?MINUTES:COMPANY).matcher(text);
            boolean correction=text.startsWith("现在") || text.startsWith("目前") || text.startsWith("以后") || text.startsWith("改")
                    || text.startsWith("我现在") || text.startsWith("我目前") || text.startsWith("我以后");
            if (correction) { values.clear();sources.clear();uncertain=false; }
            if (!match.matches()) { uncertain=true;source(sources,row,text);continue; }
            String value=match.group(1).strip();
            if (field==Field.DAILY_MINUTES && (Integer.parseInt(value)<1 || Integer.parseInt(value)>480)) { uncertain=true; }
            else if (field==Field.INTERVIEW_COMPANY && (value.matches(".*(?:可能|也许|不确定|忘了|某家|哪家|和|以及|或者|还是).*") || value.isBlank())) { uncertain=true; }
            else { values.add(field==Field.DAILY_MINUTES?Integer.toString(Integer.parseInt(value)):value); }
            source(sources,row,text);
        }
        if (sources.isEmpty()) { return new Result(field,Status.NOT_FOUND,null,List.of(),false); }
        if (uncertain || values.size()!=1) { return new Result(field,Status.CONFLICT,null,sources,false); }
        return new Result(field,Status.FOUND,values.iterator().next(),sources,false);
    }
    private static void source(List<Source> sources,MemoryRepository.Row row,String text) {
        if(sources.size()==5) { sources.removeFirst(); }
        sources.add(new Source(row.id(),text));
    }
}

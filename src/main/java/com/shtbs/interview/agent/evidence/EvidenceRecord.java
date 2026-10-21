package com.shtbs.interview.agent.evidence;

import com.fasterxml.jackson.databind.JsonNode;

public record EvidenceRecord(String evidenceId,EvidenceType type,String sourceId,String toolName,
                             String toolCallId,String content,JsonNode attributes) {
    public EvidenceRecord { attributes=attributes.deepCopy(); }
    @Override public JsonNode attributes() { return attributes.deepCopy(); }
}

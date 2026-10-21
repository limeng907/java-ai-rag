BEGIN;

ALTER TABLE agent_conversation ADD COLUMN message_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_conversation ADD COLUMN memory_retry_after TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
UPDATE agent_conversation c SET message_version=(SELECT COUNT(*) FROM agent_message m WHERE m.conversation_id=c.id);
ALTER TABLE agent_conversation ADD CONSTRAINT agent_conversation_owner UNIQUE(id,user_id);
CREATE INDEX idx_agent_memory_due ON agent_conversation(memory_retry_after,id);

CREATE TABLE agent_memory_summary (
    conversation_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    summary TEXT NOT NULL CHECK (length(trim(summary))>0),
    last_message_id BIGINT NOT NULL CHECK (last_message_id>0),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version>0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(conversation_id,user_id) REFERENCES agent_conversation(id,user_id) ON DELETE CASCADE
);

-- Durable cleanup intent: Redis may be unavailable when a conversation is deleted.
CREATE TABLE agent_memory_cache_cleanup (
    conversation_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Keep cache versions correct for every writer, including future repository paths.
CREATE FUNCTION agent_message_bump_version() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE agent_conversation SET message_version=message_version+1 WHERE id=NEW.conversation_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_message_version AFTER INSERT ON agent_message
    FOR EACH ROW EXECUTE FUNCTION agent_message_bump_version();

COMMIT;

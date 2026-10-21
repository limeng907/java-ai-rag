BEGIN;

ALTER TABLE agent_execution ADD COLUMN expires_at TIMESTAMPTZ;
ALTER TABLE agent_execution ADD COLUMN committed_at TIMESTAMPTZ;
UPDATE agent_execution SET expires_at=created_at+INTERVAL '3 minutes';
ALTER TABLE agent_execution ALTER COLUMN expires_at SET NOT NULL;
ALTER TABLE agent_execution ALTER COLUMN expires_at SET DEFAULT (CURRENT_TIMESTAMP+INTERVAL '1 minute');
CREATE INDEX agent_execution_pending_expiry ON agent_execution(expires_at)
    WHERE state NOT IN ('FINISHED','FAILED','TIMED_OUT');
ALTER TABLE interview_evaluation ADD COLUMN execution_id UUID REFERENCES agent_execution(id);

COMMIT;

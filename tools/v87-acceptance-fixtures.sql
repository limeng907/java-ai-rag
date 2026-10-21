-- Run ONLY in the disposable v87_acceptance database created for this test.
DO $$ BEGIN IF current_database() <> 'v87_acceptance' THEN RAISE EXCEPTION 'Refusing non-test database'; END IF; END $$;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
BEGIN;
INSERT INTO app_user(id,username,password) VALUES
 (1001,'v87_acceptance_a',crypt('v87-test-only',gen_salt('bf'))),
 (1002,'v87_acceptance_b',crypt('v87-test-only',gen_salt('bf')));
INSERT INTO user_learning_profile(user_id,target,level,daily_minutes) VALUES
 (1001,'Java backend','student',120),(1002,'Java backend','student',60);
INSERT INTO question(id,category,title,difficulty,content) VALUES
 (201,'Java','HashMap resize','EASY','Explain the HashMap resize mechanism'),
 (202,'Java','Spring transaction propagation','MEDIUM','Explain REQUIRES_NEW'),
 (203,'Other','CSS flexbox','EASY','Unrelated frontend topic');
INSERT INTO interview_session(id,user_id,category,difficulty,status,end_time) VALUES
 (101,1001,'Java','EASY','COMPLETED',CURRENT_TIMESTAMP-INTERVAL '3 days'),
 (102,1001,'Java','EASY','COMPLETED',CURRENT_TIMESTAMP-INTERVAL '2 days'),
 (103,1001,'Java','MEDIUM','COMPLETED',CURRENT_TIMESTAMP-INTERVAL '1 day');
INSERT INTO interview_evaluation(session_id,score,strengths,weaknesses,suggestions) VALUES
 (101,50,'[]','["HashMap resize"]','[]'),(102,60,'[]','[]','[]'),
 (103,90,'["Java basics"]','["Spring transaction propagation"]','[]');
INSERT INTO interview_turn(session_id,request_id,round_number,answer,question,skipped,status,score,correct,complete,missing_points) VALUES
 (101,gen_random_uuid(),1,'Only doubles capacity','HashMap resize',false,'COMPLETED',35,false,false,'["rehashing"]'),
 (102,gen_random_uuid(),1,'','HashMap resize',true,'COMPLETED',0,false,false,'[]'),
 (103,gen_random_uuid(),1,'Unsure','Spring transaction propagation',false,'COMPLETED',40,false,false,'["REQUIRES_NEW"]');
INSERT INTO knowledge_base(id,owner_user_id,name) VALUES(301,1001,'A private'),(302,1002,'B private');
INSERT INTO knowledge_document(id,knowledge_base_id,owner_user_id,name,status,active_version,latest_version) VALUES
 (401,301,1001,'HashMap V1','READY',1,1),(402,302,1002,'Private Spring','READY',2,2);
INSERT INTO knowledge_document_version(document_id,version,original_name,object_key,file_type,mime_type,file_size,file_hash,status) VALUES
 (401,1,'HashMap.txt','test-only/d1-v1','txt','text/plain',100,'test-d1-v1','READY'),
 (402,1,'Spring.txt','test-only/d2-v1','txt','text/plain',100,'test-d2-v1','READY'),
 (402,2,'Spring.txt','test-only/d2-v2','txt','text/plain',110,'test-d2-v2','READY');
COMMIT;
-- Document metadata fixtures do not upload objects or claim to test real retrieval.

package com.shtbs.interview.agent;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Service
public class PromptService {
    public record Template(String name, int version, String content, boolean active) {}
    private static final Set<String> NAMES = Set.of("CHAT","STUDY","INTERVIEW","EVALUATION");
    private final JdbcTemplate jdbc;
    public PromptService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Template active(String name) {
        requireName(name);
        return jdbc.query("SELECT * FROM prompt_template WHERE name=? AND active",
                (rs,row) -> new Template(name,rs.getInt("version"),rs.getString("content"),true), name)
                .stream().findFirst().orElseGet(() -> fallback(name));
    }

    private Template fallback(String name) {
        try {
            return new Template(name,0,new ClassPathResource("prompts/"+name.toLowerCase()+".txt")
                    .getContentAsString(StandardCharsets.UTF_8),true);
        } catch (IOException ex) { throw new BusinessException(ErrorCode.PROMPT_NOT_FOUND); }
    }

    public List<Template> list() {
        return jdbc.query("SELECT * FROM prompt_template ORDER BY name,version DESC",
                (rs,row) -> new Template(rs.getString("name"),rs.getInt("version"),rs.getString("content"),rs.getBoolean("active")));
    }

    public void create(String name, AgentModels.PromptRequest request) {
        requireName(name);
        try {
            jdbc.update("INSERT INTO prompt_template(name,version,content) VALUES (?,?,?)",
                    name,request.version(),request.content());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.REQUEST_CONFLICT);
        }
    }

    @Transactional
    public void activate(String name, int version) {
        requireName(name);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",Object.class,"prompt:"+name);
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM prompt_template WHERE name=? AND version=?)",Boolean.class,name,version))) {
            throw new BusinessException(ErrorCode.PROMPT_NOT_FOUND);
        }
        jdbc.update("UPDATE prompt_template SET active=FALSE WHERE name=? AND active",name);
        jdbc.update("UPDATE prompt_template SET active=TRUE WHERE name=? AND version=?",name,version);
    }

    private void requireName(String name) {
        if (!NAMES.contains(name)) { throw new BusinessException(ErrorCode.PARAM_ERROR); }
    }
}

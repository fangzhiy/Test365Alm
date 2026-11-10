package com.test365alm.server.businessfields;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;

@Service
public class ReviewerService {
    private final JdbcTemplate jdbc;
    private final CoreCommands commands;
    public ReviewerService(JdbcTemplate jdbc, CoreCommands commands) { this.jdbc=jdbc; this.commands=commands; }
    @Transactional(readOnly=true)
    public CoreCommands.Page<Candidate> list(UUID actor, UUID projectId, String search, String cursor, Integer limit, UUID principalId) {
        var p=commands.scope(actor,projectId,false,false);
        int size=CoreCommands.limit(limit);
        String query=search==null?"":search.strip();
        if(query.length()>200)throw ProjectAccessException.invalid("search must be at most 200 characters");
        var rows=jdbc.query("SELECT principal_id,display_name FROM app_business_reviewers(?,?,?,?,?,?,?)",
            (rs,i)->new Candidate(rs.getObject(1,UUID.class),rs.getString(2)),p.tenantId(),projectId,query,CoreCommands.cursor(cursor),size+1,principalId,actor);
        boolean more=rows.size()>size;
        var items=more?rows.subList(0,size):rows;
        return new CoreCommands.Page<>(items,more?items.get(items.size()-1).principalId().toString():null);
    }
    public record Candidate(UUID principalId,String displayName) { }
}

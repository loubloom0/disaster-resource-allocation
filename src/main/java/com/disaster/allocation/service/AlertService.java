package com.disaster.allocation.service;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.security.AccessControl;

@Service
public class AlertService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AccessControl access;

    public AlertService(JdbcTemplate jdbc, AuditService audit, AccessControl access) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.access = access;
    }

    @Transactional
    public List<Map<String, Object>> list() {
        access.requireGlobalOperator();
        syncConditions();
        Long actor = audit.actorId();
        return jdbc.queryForList("SELECT a.id,a.alert_key AS \"alertKey\",a.level,a.title,a.message,a.source,a.status,"
                + "a.created_at AS \"createdAt\",ack.acknowledged_at AS \"acknowledgedAt\" "
                + "FROM emergency_alerts a LEFT JOIN alert_acknowledgements ack ON ack.alert_id=a.id AND ack.user_id=? "
                + "WHERE a.status='OPEN' ORDER BY CASE a.level WHEN 'CRITICAL' THEN 1 WHEN 'WARNING' THEN 2 ELSE 3 END,a.created_at DESC",
                actor);
    }

    @Transactional
    public void acknowledge(long id) {
        access.requireGlobalOperator();
        Long actor = audit.actorId();
        if (actor == null) throw ApiException.unauthorized("Authentication is required");
        int inserted = jdbc.update("INSERT INTO alert_acknowledgements(alert_id,user_id) SELECT id,? FROM emergency_alerts "
                + "WHERE id=? AND status='OPEN' ON CONFLICT(alert_id,user_id) DO NOTHING", actor, id);
        if (inserted == 0) {
            Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM emergency_alerts WHERE id=? AND status='OPEN')", Boolean.class, id);
            if (!Boolean.TRUE.equals(exists)) throw ApiException.notFound("Open alert", id);
        } else {
            audit.record("ALERT_ACKNOWLEDGED", "ALERT", id, Map.of());
        }
    }

    private void syncConditions() {
        // Alert only on actual inventory/request conditions; do not synthesize movement or incidents.
        jdbc.update("INSERT INTO emergency_alerts(alert_key,level,title,message,source,status,updated_at) "
                + "SELECT 'resource-empty:'||id,'CRITICAL','Resource exhausted',name||' has no available units.','RESOURCE_INVENTORY','OPEN',now() "
                + "FROM resources WHERE active=true AND total_quantity>0 AND available_quantity=0 "
                + "ON CONFLICT(alert_key) DO UPDATE SET status='OPEN',updated_at=now(),resolved_at=NULL,message=EXCLUDED.message");
        jdbc.update("INSERT INTO emergency_alerts(alert_key,level,title,message,source,status,updated_at) "
                + "SELECT 'resource-low:'||id,'WARNING','Low resource reserve',name||' has '||available_quantity||' of '||total_quantity||' units available.',"
                + "'RESOURCE_INVENTORY','OPEN',now() FROM resources WHERE active=true AND total_quantity>0 AND available_quantity>0 "
                + "AND available_quantity::numeric/total_quantity<=0.10 "
                + "ON CONFLICT(alert_key) DO UPDATE SET status='OPEN',updated_at=now(),resolved_at=NULL,message=EXCLUDED.message");
        jdbc.update("UPDATE emergency_alerts SET status='RESOLVED',resolved_at=now(),updated_at=now() WHERE status='OPEN' AND "
                + "((alert_key LIKE 'resource-empty:%' AND NOT EXISTS (SELECT 1 FROM resources r WHERE alert_key='resource-empty:'||r.id AND r.active=true AND r.total_quantity>0 AND r.available_quantity=0)) "
                + "OR (alert_key LIKE 'resource-low:%' AND NOT EXISTS (SELECT 1 FROM resources r WHERE alert_key='resource-low:'||r.id AND r.active=true AND r.total_quantity>0 AND r.available_quantity>0 AND r.available_quantity::numeric/r.total_quantity<=0.10)))");
    }
}

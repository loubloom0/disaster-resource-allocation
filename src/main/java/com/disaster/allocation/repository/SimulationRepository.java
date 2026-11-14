package com.disaster.allocation.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SimulationRepository {

    private final JdbcTemplate jdbc;

    public SimulationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertRun(String name, String algorithm, Integer quantum, int workload, boolean simulation) {
        return insertRun(
                name, algorithm, quantum, workload, simulation,
                OffsetDateTime.now()
        );
    }

    public long insertRun(
            String name,
            String algorithm,
            Integer quantum,
            int workload,
            boolean simulation,
            OffsetDateTime startedAt) {

        Long id = jdbc.queryForObject(
                "INSERT INTO simulation_runs "
                        + "(name, algorithm, time_quantum, workload_size, is_simulation, started_at) "
                        + "VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class,
                name,
                algorithm,
                quantum,
                workload,
                simulation,
                startedAt
        );

        return id;
    }

    public void addMetric(long runId, String name, double value, String unit) {
        jdbc.update("INSERT INTO simulation_metrics (run_id, metric_name, metric_value, unit) VALUES (?,?,?,?) "
                + "ON CONFLICT (run_id, metric_name) DO UPDATE SET metric_value = EXCLUDED.metric_value",
                runId, name, value, unit);
    }

    public void finishRun(long runId, String status, long durationMs) {
        jdbc.update("UPDATE simulation_runs SET status=?, finished_at=now(), duration_ms=? WHERE id=?",
                status, durationMs, runId);
    }

    /** Recent runs, each with its metrics map. */
    public List<Map<String, Object>> recentRuns(int limit) {
        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT id, name, algorithm, time_quantum, workload_size, status, started_at, duration_ms, "
                        + "is_simulation FROM simulation_runs ORDER BY id DESC LIMIT ?", limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> run : runs) {
            Map<String, Object> copy = new LinkedHashMap<>(run);
            Map<String, Object> metrics = new LinkedHashMap<>();
            jdbc.query("SELECT metric_name, metric_value, unit FROM simulation_metrics WHERE run_id = ? ORDER BY id",
                    rs -> { metrics.put(rs.getString(1), rs.getDouble(2)); }, run.get("id"));
            copy.put("metrics", metrics);
            out.add(copy);
        }
        return out;
    }

    /** Written in its OWN transaction by the caller so the log survives the rollback it describes. */
    public void logFailure(Long requestId, String eventType, String point, String detail, Boolean verified) {
        jdbc.update("INSERT INTO failure_recovery_logs (request_id, event_type, injection_point, detail, "
                + "state_verified) VALUES (?,?,?,?,?)", requestId, eventType, point, detail, verified);
    }

    public List<Map<String, Object>> recentFailures(int limit) {
        return jdbc.queryForList("SELECT * FROM failure_recovery_logs ORDER BY id DESC LIMIT ?", limit);
    }
}

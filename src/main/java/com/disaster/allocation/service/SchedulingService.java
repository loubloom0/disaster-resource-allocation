package com.disaster.allocation.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.Dto.AllocationOutcome;
import com.disaster.allocation.model.Dto.QueueRunResult;
import com.disaster.allocation.model.Dto.ResourceRequest;
import com.disaster.allocation.model.Dto.SimInput;
import com.disaster.allocation.model.Dto.SimJob;
import com.disaster.allocation.repository.RequestRepository;
import com.disaster.allocation.repository.SimulationRepository;
import com.disaster.allocation.allocation.AllocationEngine;
import com.disaster.allocation.scheduler.FcfsScheduler;
import com.disaster.allocation.scheduler.JobSpec;
import com.disaster.allocation.scheduler.PriorityScheduler;
import com.disaster.allocation.scheduler.RoundRobinScheduler;
import com.disaster.allocation.scheduler.SchedulingAlgorithm;
import com.disaster.allocation.scheduler.SchedulingResult;
import com.disaster.allocation.scheduler.SchedulingSimulator;

/**
 * Connects the (unchanged) scheduler classes to the database and to the allocation engine.
 * The scheduler only decides ORDER; the engine decides whether units can really be given.
 */
@Service
public class SchedulingService {

    private final SchedulingSimulator simulator = new SchedulingSimulator();
    private final RequestRepository requests;
    private final SimulationRepository simulations;
    private final AllocationEngine engine;

    public SchedulingService(RequestRepository requests, SimulationRepository simulations, AllocationEngine engine) {
        this.requests = requests;
        this.simulations = simulations;
        this.engine = engine;
    }

    public static SchedulingAlgorithm algorithm(String name, Integer quantum) {
        if (name == null) {
            throw ApiException.badRequest("algorithm is required (FCFS, PRIORITY or ROUND_ROBIN)");
        }
        return switch (name.toUpperCase()) {
            case "FCFS" -> new FcfsScheduler();
            case "PRIORITY" -> new PriorityScheduler();
            case "ROUND_ROBIN", "RR" -> new RoundRobinScheduler(quantum == null ? 2 : quantum);
            default -> throw ApiException.badRequest("unknown algorithm: " + name);
        };
    }

    /** Pure what-if run on user-supplied jobs (the Scheduling Simulator page). */
    public Map<String, Object> simulate(SimInput in) {
        SchedulingAlgorithm algo = algorithm(in.algorithm(), in.quantum());
        List<JobSpec> specs = new ArrayList<>();
        long seq = 1;
        for (SimJob j : in.jobs()) {
            specs.add(new JobSpec(j.id(), j.priority(), seq++, j.arrivalTime(), j.workUnits()));
        }
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        long startedNanos = System.nanoTime();

        SchedulingResult result = simulator.run(specs, algo);
        Map<String, Object> out = describe(result, algo);

        if (Boolean.TRUE.equals(in.save())) {
            long runId = persist(
                    in.name() == null ? "Manual simulation" : in.name(),
                    algo,
                    result,
                    startedAt,
                    startedNanos,
                    true
            );
            out.put("runId", runId);
        }
        return out;
    }

    /** Shows the order the scheduler WOULD use for the current queue, without allocating anything. */
    public Map<String, Object> previewQueue(String algorithm, Integer quantum) {
        SchedulingAlgorithm algo = algorithm(algorithm, quantum);
        List<ResourceRequest> queue = requests.queue();
        SchedulingResult result = simulator.run(toSpecs(queue), algo);
        return describe(result, algo);
    }

    /**
     * Workflow steps 3-11 for the whole queue: the chosen scheduler orders the PENDING/WAITING requests,
     * then each request goes through the allocation engine in that order, one transaction per request
     * (one failure never undoes a different request's committed allocation).
     */
    public QueueRunResult runQueue(String algorithm, Integer quantum, boolean injectFailure) {
        SchedulingAlgorithm algo = algorithm(algorithm, quantum);
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        long startedNanos = System.nanoTime();
        List<ResourceRequest> queue = requests.queue();
        SchedulingResult result = simulator.run(toSpecs(queue), algo);
        // Order requests are served in = order they complete in the simulated schedule.
        List<Long> order = result.completionOrder();
        List<AllocationOutcome> outcomes = new ArrayList<>();
        for (long id : order) {
            try {
                outcomes.add(engine.allocate(id, injectFailure, algo.name()));
            } catch (AllocationEngine.InjectedFailure e) {
                // Transaction already rolled back by Spring; log in a separate (autocommit) statement.
                simulations.logFailure(id, "INJECTED_FAILURE", "AFTER_WRITES", e.getMessage(), Boolean.TRUE);
                simulations.logFailure(id, "ROLLBACK", "AFTER_WRITES", "All writes for request " + id
                        + " were rolled back", Boolean.TRUE);
                outcomes.add(new AllocationOutcome(id, "ROLLED_BACK", e.getMessage(), 0, null, algo.name(), null));
            } catch (RuntimeException e) {
                simulations.logFailure(id, "DB_ERROR", null, String.valueOf(e.getMessage()), Boolean.FALSE);
                outcomes.add(new AllocationOutcome(id, "ERROR", String.valueOf(e.getMessage()), 0, null,
                        algo.name(), null));
            }
        }
        persist("Queue run (" + algo.name() + ")", algo, result, startedAt, startedNanos, false);
        return new QueueRunResult(algo.name(), algo instanceof RoundRobinScheduler rr ? rr.quantum() : null, order,
                outcomes);
    }

    private static List<JobSpec> toSpecs(List<ResourceRequest> queue) {
        List<JobSpec> specs = new ArrayList<>();
        // All queued requests are treated as arriving together (time 0); arrival_seq keeps FCFS order.
        for (ResourceRequest r : queue) {
            specs.add(new JobSpec(r.id(), r.priority(), r.arrivalSeq(), 0, r.workUnits()));
        }
        return specs;
    }

    private long persist(
            String name,
            SchedulingAlgorithm algo,
            SchedulingResult r,
            OffsetDateTime startedAt,
            long startedNanos,
            boolean sim) {

        Integer q = algo instanceof RoundRobinScheduler rr ? rr.quantum() : null;

        long runId = simulations.insertRun(
                name,
                algo.name(),
                q,
                r.jobs().size(),
                sim,
                startedAt
        );

        simulations.addMetric(runId, "avg_waiting_time", r.averageWaiting(), "units");
        simulations.addMetric(runId, "avg_turnaround_time", r.averageTurnaround(), "units");
        simulations.addMetric(runId, "avg_response_time", r.averageResponse(), "units");
        simulations.addMetric(runId, "makespan", r.makespan(), "units");

        // Measure after metrics are persisted.
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;

        simulations.finishRun(runId, "COMPLETED", durationMs);

        return runId;
    }

    private static Map<String, Object> describe(SchedulingResult r, SchedulingAlgorithm algo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("algorithm", r.algorithm());
        if (algo instanceof RoundRobinScheduler rr) {
            m.put("quantum", rr.quantum());
        }
        m.put("executionOrder", r.completionOrder());
        m.put("timeline", r.timeline());
        m.put("jobs", r.jobs());
        m.put("averageWaiting", r.averageWaiting());
        m.put("averageTurnaround", r.averageTurnaround());
        m.put("averageResponse", r.averageResponse());
        m.put("makespan", r.makespan());
        return m;
    }
}

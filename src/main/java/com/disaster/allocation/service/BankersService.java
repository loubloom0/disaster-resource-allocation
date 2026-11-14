package com.disaster.allocation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.disaster.allocation.allocation.BankersSnapshot;
import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.Dto.BankersInput;
import com.disaster.allocation.repository.AllocationRepository;
import com.disaster.allocation.repository.OrganizationRepository;
import com.disaster.allocation.repository.ResourceRepository;
import com.disaster.allocation.safety.BankersAlgorithm;
import com.disaster.allocation.safety.RequestDecision;
import com.disaster.allocation.safety.SafetyResult;

/** Exposes the existing BankersAlgorithm both on user-typed matrices and on the live database state. */
@Service
public class BankersService {

    private final ResourceRepository resources;
    private final OrganizationRepository orgs;
    private final AllocationRepository allocations;

    public BankersService(ResourceRepository resources, OrganizationRepository orgs,
                          AllocationRepository allocations) {
        this.resources = resources;
        this.orgs = orgs;
        this.allocations = allocations;
    }

    /** Simulator: arbitrary matrices typed by the user. */
    public Map<String, Object> evaluate(BankersInput in) {
        BankersAlgorithm banker;
        try {
            banker = new BankersAlgorithm(in.available(), in.max(), in.allocation());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return describe(banker, in.process(), in.request());
    }

    /** The REAL system state: matrices built from organizations, claims, ACTIVE allocations and inventory. */
    public Map<String, Object> liveState(Long organizationId, Long resourceId, Integer quantity) {
        BankersSnapshot snap = BankersSnapshot.build(resources.findAll(), orgs.findAll(), orgs.allClaims(),
                allocations.activeHoldings(), organizationId);
        BankersAlgorithm banker = snap.toAlgorithm();
        Integer process = null;
        int[] request = null;
        if (organizationId != null && resourceId != null && quantity != null) {
            process = snap.orgIndex(organizationId);
            int col = snap.resourceIndex(resourceId);
            if (process < 0 || col < 0) {
                throw ApiException.badRequest("Organization or (reusable, active) resource not part of the Banker's model");
            }
            request = new int[snap.resourceIds.size()];
            request[col] = quantity;
        }
        Map<String, Object> out = describe(banker, process, request);
        out.put("processNames", snap.orgNames);
        out.put("processIds", snap.orgIds);
        out.put("resourceNames", snap.resourceNames);
        out.put("resourceIds", snap.resourceIds);
        out.put("warnings", snap.warnings);
        out.put("note", "Only reusable resources are modelled; consumables use a plain inventory check.");
        return out;
    }

    private static Map<String, Object> describe(BankersAlgorithm banker, Integer process, int[] request) {
        Map<String, Object> m = new LinkedHashMap<>();
        SafetyResult safety = banker.checkSafety();
        m.put("available", banker.available());
        m.put("allocation", banker.allocation());
        m.put("max", maxOf(banker));
        m.put("need", banker.need());
        m.put("safe", safety.safe());
        m.put("safeSequence", safety.safeSequence());
        m.put("explanation", safety.explanation());
        if (process != null && request != null) {
            try {
                RequestDecision d = banker.evaluateRequest(process, request);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("process", process);
                r.put("request", request);
                r.put("outcome", d.outcome().name());
                r.put("granted", d.granted());
                r.put("explanation", d.explanation());
                r.put("safeSequence", d.safeSequence());
                m.put("requestEvaluation", r);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }
        }
        return m;
    }

    /** Max = Allocation + Need (the algorithm exposes need and allocation, not max). */
    private static int[][] maxOf(BankersAlgorithm b) {
        int[][] need = b.need();
        int[][] alloc = b.allocation();
        int[][] max = new int[need.length][];
        for (int i = 0; i < need.length; i++) {
            max[i] = new int[need[i].length];
            for (int j = 0; j < need[i].length; j++) {
                max[i][j] = need[i][j] + alloc[i][j];
            }
        }
        return max;
    }
}

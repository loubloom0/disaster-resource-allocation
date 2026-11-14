package com.disaster.allocation.integration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceRequest;

/** REST CRUD, request lifecycle, validation errors, and constraints enforced by PostgreSQL itself. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ApiAndConstraintsIT extends DrasIntegrationBase {

    @Autowired MockMvc mvc;

    private static String json(String s) { return s.replace('\'', '"'); }

    // ---------------------------------------------------------- organizations
    @Test
    void organizationCrudOverRest() throws Exception {
        String name = "T-org-" + UUID.randomUUID();
        String created = mvc.perform(post("/api/organizations").contentType(MediaType.APPLICATION_JSON)
                .content(json("{'name':'" + name + "','orgType':'SHELTER','location':'Test'}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(name))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        try {
            mvc.perform(get("/api/organizations/" + id)).andExpect(status().isOk());
            mvc.perform(put("/api/organizations/" + id).contentType(MediaType.APPLICATION_JSON)
                    .content(json("{'name':'" + name + "','orgType':'HOSPITAL','location':'Elsewhere'}")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.location").value("Elsewhere"));
            // duplicate name -> UNIQUE constraint -> 409
            mvc.perform(post("/api/organizations").contentType(MediaType.APPLICATION_JSON)
                    .content(json("{'name':'" + name + "','orgType':'SHELTER','location':'x'}")))
                    .andExpect(status().isConflict());
        } finally {
            mvc.perform(delete("/api/organizations/" + id)).andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/organizations/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void invalidOrganizationIsRejectedWith400() throws Exception {
        mvc.perform(post("/api/organizations").contentType(MediaType.APPLICATION_JSON)
                .content(json("{'name':'','orgType':'BANK','location':'x'}"))).andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------- resources
    @Test
    void resourceCrudOverRestAndTotalCannotDropBelowAllocated() throws Exception {
        String name = "T-res-" + UUID.randomUUID();
        String created = mvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON)
                .content(json("{'name':'" + name + "','category':'WATER','totalQuantity':50,'reusable':false,'location':'T'}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.availableQuantity").value(50))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        try {
            mvc.perform(put("/api/resources/" + id).contentType(MediaType.APPLICATION_JSON)
                    .content(json("{'name':'" + name + "','category':'WATER','totalQuantity':80,'reusable':false,'location':'T'}")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.availableQuantity").value(80));
            mvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON)
                    .content(json("{'name':'x','category':'WATER','totalQuantity':-5,'location':'T'}")))
                    .andExpect(status().isBadRequest());
        } finally {
            mvc.perform(delete("/api/resources/" + id)).andExpect(status().isNoContent());
        }
    }

    // --------------------------------------------------------------- requests
    @Test
    void requestLifecycleOverRest() throws Exception {
        Organization o = newOrg();
        Resource r = newResource(20, true);
        // no claim declared yet -> refused
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(json("{'organizationId':" + o.id() + ",'resourceId':" + r.id() + ",'quantity':3,'priority':3}")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/organizations/" + o.id() + "/claims").contentType(MediaType.APPLICATION_JSON)
                .content(json("{'resourceId':" + r.id() + ",'maxClaim':10}"))).andExpect(status().isOk());
        // above claim -> refused
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(json("{'organizationId':" + o.id() + ",'resourceId':" + r.id() + ",'quantity':11,'priority':3}")))
                .andExpect(status().isBadRequest());
        String body = mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(json("{'organizationId':" + o.id() + ",'resourceId':" + r.id() + ",'quantity':3,'priority':4}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long reqId = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(post("/api/requests/" + reqId + "/allocate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALLOCATED"));
        // allocating twice is a conflict
        mvc.perform(post("/api/requests/" + reqId + "/allocate")).andExpect(status().isConflict());
        // an ALLOCATED request cannot be rejected by hand
        mvc.perform(put("/api/requests/" + reqId + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(json("{'status':'REJECTED'}"))).andExpect(status().isConflict());
        mvc.perform(get("/api/dashboard/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.activeAllocations").isNumber());
    }

    // ------------------------------------------------- database constraints
    @Test
    void databaseRejectsInventoryThatBreaksTheInvariant() {
        Resource r = newResource(10, true);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE resources SET available_quantity = available_quantity + 1 WHERE id = ?", r.id()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE resources SET available_quantity = -1, allocated_quantity = 11 WHERE id = ?", r.id()));
    }

    @Test
    void databaseRejectsInvalidRequestRows() {
        Organization o = newOrg();
        Resource r = newResource(10, true);
        claim(o, r, 5);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO resource_requests (organization_id, resource_id, requested_quantity, priority, max_claim) VALUES (?,?,?,?,?)",
                o.id(), r.id(), 3, 9, 5));                 // priority outside 1..5
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO resource_requests (organization_id, resource_id, requested_quantity, priority, max_claim) VALUES (?,?,?,?,?)",
                o.id(), r.id(), 6, 3, 5));                 // request above max_claim
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO resource_requests (organization_id, resource_id, requested_quantity, priority, max_claim) VALUES (?,?,?,?,?)",
                999999999L, r.id(), 1, 3, 5));             // foreign key
    }
}

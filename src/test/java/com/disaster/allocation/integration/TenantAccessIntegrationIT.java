package com.disaster.allocation.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceRequest;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TenantAccessIntegrationIT extends DrasIntegrationBase {
    @Autowired MockMvc mvc;
    private final List<Long> userIds = new ArrayList<>();

    @Test
    void organizationRolesAreScopedAndCannotUseIdsToCrossTenantBoundaries() throws Exception {
        Organization own = newOrg();
        Organization other = newOrg();
        jdbc.update("UPDATE organizations SET contact_info=? WHERE id=?", "private-own-contact", own.id());
        Resource resource = newResource(30, false);
        claim(own, resource, 10);
        claim(other, resource, 10);
        long managerId = user(own.id(), "ORGANIZATION_MANAGER");
        long memberId = user(own.id(), "ORGANIZATION_MEMBER");
        long otherMemberId = user(other.id(), "ORGANIZATION_MEMBER");
        long ownMemberRequest = reqService.submit(new com.disaster.allocation.model.Dto.RequestInput(own.id(), resource.id(), 2, 3, 1), memberId).id();
        long ownManagerRequest = reqService.submit(new com.disaster.allocation.model.Dto.RequestInput(own.id(), resource.id(), 1, 2, 1), null).id();
        long otherRequest = reqService.submit(new com.disaster.allocation.model.Dto.RequestInput(other.id(), resource.id(), 2, 3, 1), otherMemberId).id();
        long ownManagerAllocation = jdbc.queryForObject("INSERT INTO allocations(request_id,resource_id,quantity) "
                + "VALUES (?,?,1) RETURNING id", Long.class, ownManagerRequest, resource.id());
        long ownMemberAllocation = jdbc.queryForObject("INSERT INTO allocations(request_id,resource_id,quantity) "
                + "VALUES (?,?,2) RETURNING id", Long.class, ownMemberRequest, resource.id());
        long otherAllocation = jdbc.queryForObject("INSERT INTO allocations(request_id,resource_id,quantity,status,released_at,released_quantity) "
                + "VALUES (?,?,1,'RELEASED',now(),1) RETURNING id", Long.class, otherRequest, resource.id());

        mvc.perform(get("/api/organizations").with(manager(managerId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(own.id())).andExpect(jsonPath("$[0].contactInfo").value("private-own-contact"));
        mvc.perform(get("/api/organizations").with(member(memberId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(own.id())).andExpect(jsonPath("$[0].contactInfo").doesNotExist());
        mvc.perform(get("/api/organizations/{id}", other.id()).with(manager(managerId, own.id())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/organizations/{id}/claims", other.id()).with(manager(managerId, own.id())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/organizations/{id}/claims", own.id()).with(member(memberId, own.id())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/organizations/{id}/claims", own.id()).with(member(memberId, own.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resourceId\":" + resource.id() + ",\"maxClaim\":5}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/requests").with(manager(managerId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/requests").with(member(memberId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(ownMemberRequest));
        mvc.perform(get("/api/requests/{id}", otherRequest).with(manager(managerId, own.id())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/requests/{id}", ownManagerRequest).with(member(memberId, own.id())))
                .andExpect(status().isForbidden());
        var memberSubmission = mvc.perform(post("/api/requests").with(member(memberId, own.id())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":" + other.id() + ",\"resourceId\":" + resource.id()
                                + ",\"quantity\":1,\"priority\":1,\"workUnits\":1}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.organizationId").value(own.id()))
                .andReturn();
        long memberSubmittedId = Long.parseLong(com.jayway.jsonpath.JsonPath
                .read(memberSubmission.getResponse().getContentAsString(), "$.id").toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resource_requests "
                        + "WHERE id=? AND organization_id=? AND created_by_user_id=?", Integer.class,
                memberSubmittedId, own.id(), memberId));

        mvc.perform(get("/api/allocations").with(manager(managerId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(ownMemberAllocation))
                .andExpect(jsonPath("$[0].organizationId").value(own.id()))
                .andExpect(jsonPath("$[0].resourceName").value(resource.name()))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].allocatedAt").isNotEmpty())
                .andExpect(jsonPath("$[1].id").value(ownManagerAllocation));
        mvc.perform(get("/api/allocations").with(member(memberId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(ownMemberAllocation))
                .andExpect(jsonPath("$[0].organizationId").value(own.id()))
                .andExpect(jsonPath("$[0].quantity").value(2));
        mvc.perform(get("/api/allocations/{id}", ownManagerAllocation).with(member(memberId, own.id())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/allocations/{id}", otherAllocation).with(manager(managerId, own.id())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/allocations/{id}", otherAllocation).with(member(memberId, own.id())))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/dashboard/stats").with(manager(managerId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingRequests").value(3))
                .andExpect(jsonPath("$.totalUnits").doesNotExist()).andExpect(jsonPath("$.organizations").value(1));
        mvc.perform(get("/api/dashboard/stats").with(member(memberId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingRequests").value(2))
                .andExpect(jsonPath("$.totalUnits").doesNotExist());
        mvc.perform(get("/api/metrics").with(manager(managerId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("ORGANIZATION"));
        mvc.perform(get("/api/metrics").with(member(memberId, own.id())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(manager(managerId, own.id())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/resources").with(member(memberId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].totalQuantity").doesNotExist())
                .andExpect(jsonPath("$[0].location").doesNotExist())
                .andExpect(jsonPath("$[0].category").isNotEmpty())
                .andExpect(jsonPath("$[0].availableQuantity").isNumber())
                .andExpect(jsonPath("$[0].active").doesNotExist());
        mvc.perform(get("/api/resources").with(manager(managerId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].category").isNotEmpty())
                .andExpect(jsonPath("$[0].availableQuantity").isNumber())
                .andExpect(jsonPath("$[0].active").doesNotExist());
        var managerSubmission = mvc.perform(post("/api/requests").with(manager(managerId, own.id())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":" + other.id() + ",\"resourceId\":" + resource.id()
                                + ",\"quantity\":1,\"priority\":1,\"workUnits\":1}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.organizationId").value(own.id()))
                .andReturn();
        long managerSubmittedId = Long.parseLong(com.jayway.jsonpath.JsonPath
                .read(managerSubmission.getResponse().getContentAsString(), "$.id").toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resource_requests "
                        + "WHERE id=? AND organization_id=? AND created_by_user_id=?", Integer.class,
                managerSubmittedId, own.id(), managerId));
    }

    @Test
    void dispatcherHasOperationalAccessWithoutOrganizationAdministration() throws Exception {
        Organization own = newOrg();
        long dispatcherId = globalUser("DISPATCHER");
        long adminId = globalUser("SYSTEM_ADMIN");
        mvc.perform(get("/api/resources").with(dispatcher(dispatcherId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].totalQuantity").isNumber())
                .andExpect(jsonPath("$[0].active").isNotEmpty());
        mvc.perform(get("/api/resources").with(systemAdmin(adminId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].totalQuantity").isNumber())
                .andExpect(jsonPath("$[0].active").isNotEmpty());
        mvc.perform(get("/api/organizations").with(dispatcher(dispatcherId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].contactInfo").doesNotExist());
        mvc.perform(post("/api/organizations").with(dispatcher(dispatcherId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No Dispatcher Org\",\"orgType\":\"SHELTER\",\"location\":\"Nowhere\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/organizations/{id}/members", own.id()).with(dispatcher(dispatcherId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/alerts").with(dispatcher(dispatcherId)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/approvals").with(dispatcher(dispatcherId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanPartiallyAndFullyReleaseOnlyOwnReusableAllocations() throws Exception {
        Organization own = newOrg();
        Organization other = newOrg();
        Resource reusable = newResource(10, true);
        claim(own, reusable, 3);
        claim(other, reusable, 3);
        long managerId = user(own.id(), "ORGANIZATION_MANAGER");
        long otherManagerId = user(other.id(), "ORGANIZATION_MANAGER");
        long memberId = user(own.id(), "ORGANIZATION_MEMBER");

        long requestId = request(own, reusable, 3, 4).id();
        long allocationId = engine.allocate(requestId, false, "TEST").allocationId();

        mvc.perform(post("/api/allocations/{id}/release", allocationId).with(manager(managerId, own.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/allocations/{id}/release", allocationId).with(manager(managerId, own.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/allocations/{id}/release", allocationId).with(manager(managerId, own.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":4}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/allocations/{id}/release", allocationId).with(manager(otherManagerId, other.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/allocations/{id}/release", allocationId).with(member(memberId, own.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/allocations/{id}/release", allocationId).with(manager(managerId, own.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.releasedQuantity").value(1)).andExpect(jsonPath("$.remainingQuantity").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertEquals(8, reload(reusable).availableQuantity());
        assertEquals(2, reload(reusable).allocatedQuantity());
        assertEquals(2, activeAllocationUnits(allocationId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM allocation_releases WHERE allocation_id=?", Integer.class, allocationId));

        mvc.perform(post("/api/allocations/{id}/release", allocationId).with(manager(managerId, own.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.releasedQuantity").value(3))
                .andExpect(jsonPath("$.remainingQuantity").value(0)).andExpect(jsonPath("$.status").value("RELEASED"));
        assertEquals(10, reload(reusable).availableQuantity());
        assertEquals(0, reload(reusable).allocatedQuantity());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM allocation_releases WHERE allocation_id=?", Integer.class, allocationId));
        assertEquals(3, jdbc.queryForObject("SELECT sum(released_quantity) FROM allocation_releases WHERE allocation_id=?", Integer.class, allocationId));

        long dispatcherId = globalUser("DISPATCHER");
        long adminId = globalUser("SYSTEM_ADMIN");
        long dispatcherAllocation = engine.allocate(request(own, reusable, 1, 3).id(), false, "TEST").allocationId();
        mvc.perform(post("/api/allocations/{id}/release", dispatcherAllocation).with(dispatcher(dispatcherId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        long adminAllocation = engine.allocate(request(own, reusable, 1, 3).id(), false, "TEST").allocationId();
        mvc.perform(post("/api/allocations/{id}/release", adminAllocation).with(systemAdmin(adminId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));

        Resource consumable = newResource(5, false);
        claim(own, consumable, 3);
        ResourceRequest consumableRequest = request(own, consumable, 2, 4);
        long consumableAllocation = engine.allocate(consumableRequest.id(), false, "TEST").allocationId();
        mvc.perform(post("/api/allocations/{id}/release", consumableAllocation).with(manager(managerId, own.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT released_quantity FROM allocations WHERE id=?", Integer.class, consumableAllocation));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM allocation_releases WHERE allocation_id=?", Integer.class, consumableAllocation));
    }

    @Autowired com.disaster.allocation.allocation.AllocationEngine engine;

    private int activeAllocationUnits(long allocationId) {
        return jdbc.queryForObject("SELECT quantity-released_quantity FROM allocations WHERE id=? AND status='ACTIVE'",
                Integer.class, allocationId);
    }

    @Test
    void analyticsUsesRoleScopeAndRealAllocationHistory() throws Exception {
        Organization own = newOrg();
        Organization other = newOrg();
        Resource resource = newResource(20, false);
        claim(own, resource, 10);
        claim(other, resource, 10);
        long managerId = user(own.id(), "ORGANIZATION_MANAGER");
        long memberId = user(own.id(), "ORGANIZATION_MEMBER");
        long otherMemberId = user(other.id(), "ORGANIZATION_MEMBER");
        long dispatcherId = globalUser("DISPATCHER");
        long adminId = globalUser("SYSTEM_ADMIN");

        var memberRequest = reqService.submit(
                new com.disaster.allocation.model.Dto.RequestInput(own.id(), resource.id(), 4, 5, 1), memberId);
        reqService.submit(new com.disaster.allocation.model.Dto.RequestInput(own.id(), resource.id(), 3, 2, 1), null);
        var otherRequest = reqService.submit(
                new com.disaster.allocation.model.Dto.RequestInput(other.id(), resource.id(), 7, 1, 1), otherMemberId);
        long ownReleasedAllocation = jdbc.queryForObject("INSERT INTO allocations(request_id,resource_id,quantity,status,allocated_at,released_at,released_quantity) "
                        + "VALUES (?,?,2,'RELEASED',now()-interval '3 days',now()-interval '2 days',2) RETURNING id",
                Long.class, memberRequest.id(), resource.id());
        jdbc.update("INSERT INTO allocation_releases(allocation_id,resource_id,organization_id,released_quantity,released_at) "
                        + "VALUES (?,?,?,2,now()-interval '2 days')", ownReleasedAllocation, resource.id(), own.id());
        long otherReleasedAllocation = jdbc.queryForObject("INSERT INTO allocations(request_id,resource_id,quantity,status,allocated_at,released_at,released_quantity) "
                        + "VALUES (?,?,5,'RELEASED',now()-interval '3 days',now()-interval '2 days',5) RETURNING id",
                Long.class, otherRequest.id(), resource.id());
        jdbc.update("INSERT INTO allocation_releases(allocation_id,resource_id,organization_id,released_quantity,released_at) "
                        + "VALUES (?,?,?,5,now()-interval '2 days')", otherReleasedAllocation, resource.id(), other.id());

        mvc.perform(get("/api/analytics/dashboard?days=7").with(manager(managerId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("ORGANIZATION"))
                .andExpect(jsonPath("$.requests.total").value(2))
                .andExpect(jsonPath("$.requests.critical").value(1))
                .andExpect(jsonPath("$.resources").doesNotExist())
                .andExpect(jsonPath("$.allocationTrends[0].allocatedUnits").value(2))
                .andExpect(jsonPath("$.allocationTrends[1].releasedUnits").value(2));
        mvc.perform(get("/api/analytics/dashboard?days=7").with(member(memberId, own.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("REQUESTER"))
                .andExpect(jsonPath("$.requests.total").value(1))
                .andExpect(jsonPath("$.requests.critical").value(1))
                .andExpect(jsonPath("$.allocationTrends[0].allocatedUnits").value(2));
        mvc.perform(get("/api/analytics/dashboard?days=7").with(member(otherMemberId, other.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requests.total").value(1))
                .andExpect(jsonPath("$.allocationTrends[0].allocatedUnits").value(5));
        mvc.perform(get("/api/analytics/dashboard?days=7").with(dispatcher(dispatcherId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("SYSTEM"))
                .andExpect(jsonPath("$.resources.totalUnits").isNumber())
                .andExpect(jsonPath("$.resourceCategories[0].category").isNotEmpty());
        mvc.perform(get("/api/analytics/dashboard?days=7").with(systemAdmin(adminId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("SYSTEM"))
                .andExpect(jsonPath("$.resources.resourceCount").isNumber());
        mvc.perform(get("/api/analytics/dashboard?days=366").with(dispatcher(dispatcherId)))
                .andExpect(status().isBadRequest());
    }

    @AfterEach
    void cleanUsers() {
        for (long userId : userIds) jdbc.update("DELETE FROM app_users WHERE id=?", userId);
        super.cleanup();
    }

    private long user(long organizationId, String role) {
        String email = "tenant-" + UUID.randomUUID() + "@example.test";
        long id = jdbc.queryForObject("INSERT INTO app_users(email,password_hash,full_name,status) VALUES (?,?,?,'ACTIVE') RETURNING id",
                Long.class, email, "unused-test-hash", "Tenant Test User");
        jdbc.update("INSERT INTO organization_memberships(user_id,organization_id,role,status) VALUES (?,?,?,'ACTIVE')",
                id, organizationId, role);
        userIds.add(id);
        return id;
    }

    private long globalUser(String role) {
        String email = "global-" + UUID.randomUUID() + "@example.test";
        long id = jdbc.queryForObject("INSERT INTO app_users(email,password_hash,full_name,global_role,status) VALUES (?,?,?,?,'ACTIVE') RETURNING id",
                Long.class, email, "unused-test-hash", "Global Test User", role);
        userIds.add(id);
        return id;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor manager(long id, long organizationId) {
        return jwt().jwt(j -> j.subject(String.valueOf(id)).claim("organization_id", organizationId)
                .claim("roles", List.of("ORGANIZATION_MANAGER")))
                .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZATION_MANAGER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor member(long id, long organizationId) {
        return jwt().jwt(j -> j.subject(String.valueOf(id)).claim("organization_id", organizationId)
                .claim("roles", List.of("ORGANIZATION_MEMBER")))
                .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZATION_MEMBER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor dispatcher(long id) {
        return jwt().jwt(j -> j.subject(String.valueOf(id)).claim("roles", List.of("DISPATCHER")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor systemAdmin(long id) {
        return jwt().jwt(j -> j.subject(String.valueOf(id)).claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
    }
}

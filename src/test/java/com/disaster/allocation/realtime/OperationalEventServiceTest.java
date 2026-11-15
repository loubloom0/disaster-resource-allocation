package com.disaster.allocation.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class OperationalEventServiceTest {
    private OperationalEventService events;
    private final ArrayDeque<SseEmitter> emitters = new ArrayDeque<>();

    @BeforeEach
    void setUp() { events = new OperationalEventService(emitters::remove, false); }

    @AfterEach
    void tearDown() { events.shutdown(); }

    @Test
    void systemEventsAreDeliveredOnlyToGlobalOperators() throws Exception {
        var admin = subscribe(1, "SYSTEM_ADMIN", null);
        var dispatcher = subscribe(2, "DISPATCHER", null);
        var manager = subscribe(3, "ORGANIZATION_MANAGER", 10L);
        var member = subscribe(4, "ORGANIZATION_MEMBER", 10L);

        events.publish(OperationalEventService.EventType.TEST_EVENT,
                OperationalEventService.Audience.SYSTEM_OPERATORS, null, null, Map.of("message", "test"));

        verify(admin).send(any(SseEmitter.SseEventBuilder.class));
        verify(dispatcher).send(any(SseEmitter.SseEventBuilder.class));
        verify(manager, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(member, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void organizationAndUserEventsRespectTenantAndMemberVisibility() throws Exception {
        var admin = subscribe(1, "SYSTEM_ADMIN", null);
        var dispatcher = subscribe(2, "DISPATCHER", null);
        var managerA = subscribe(3, "ORGANIZATION_MANAGER", 10L);
        var managerB = subscribe(5, "ORGANIZATION_MANAGER", 20L);
        var memberA = subscribe(4, "ORGANIZATION_MEMBER", 10L);
        var otherMemberA = subscribe(6, "ORGANIZATION_MEMBER", 10L);
        var memberB = subscribe(7, "ORGANIZATION_MEMBER", 20L);

        events.publish(OperationalEventService.EventType.REQUEST_CREATED,
                OperationalEventService.Audience.ORGANIZATION_MANAGERS, 10L, null, Map.of("requestId", 71));

        verify(admin).send(any(SseEmitter.SseEventBuilder.class));
        verify(dispatcher).send(any(SseEmitter.SseEventBuilder.class));
        verify(managerA).send(any(SseEmitter.SseEventBuilder.class));
        verify(managerB, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(memberA, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherMemberA, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(memberB, never()).send(any(SseEmitter.SseEventBuilder.class));

        events.publish(OperationalEventService.EventType.TEST_EVENT,
                OperationalEventService.Audience.USER, 10L, 4L, Map.of("message", "targeted"));

        verify(admin, org.mockito.Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(dispatcher, org.mockito.Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(managerA, org.mockito.Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(managerB, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(memberA).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherMemberA, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(memberB, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sharedResourceCatalogueEventsMatchExistingCatalogueVisibility() throws Exception {
        var admin = subscribe(1, "SYSTEM_ADMIN", null);
        var dispatcher = subscribe(2, "DISPATCHER", null);
        var manager = subscribe(3, "ORGANIZATION_MANAGER", 10L);
        var member = subscribe(4, "ORGANIZATION_MEMBER", 20L);

        events.publish(OperationalEventService.EventType.RESOURCE_AVAILABILITY_CHANGED,
                OperationalEventService.Audience.SHARED_RESOURCE_CATALOG, null, null,
                Map.of("resourceName", "Ambulance", "availableQuantity", 3));

        verify(admin).send(any(SseEmitter.SseEventBuilder.class));
        verify(dispatcher).send(any(SseEmitter.SseEventBuilder.class));
        verify(manager).send(any(SseEmitter.SseEventBuilder.class));
        verify(member).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void heartbeatAndCompletionCleanUpDisconnectedClientsAndAllowReconnect() throws Exception {
        AtomicReference<Runnable> completion = new AtomicReference<>();
        SseEmitter first = org.mockito.Mockito.mock(SseEmitter.class);
        doAnswer(call -> { completion.set(call.getArgument(0)); return null; })
                .when(first).onCompletion(any(Runnable.class));
        emitters.add(first);
        events.connect(identity(11, "ORGANIZATION_MEMBER", 42L));
        assertEquals(1, events.connectedClientCount());
        completion.get().run();
        assertEquals(0, events.connectedClientCount());

        SseEmitter reconnected = subscribe(12, "ORGANIZATION_MEMBER", 42L);
        assertEquals(1, events.connectedClientCount());
        events.heartbeatNow();
        verify(reconnected).send(any(SseEmitter.SseEventBuilder.class));
        events.publish(OperationalEventService.EventType.TEST_EVENT,
                OperationalEventService.Audience.USER, 42L, 12L, Map.of("message", "reconnected"));
        verify(reconnected, org.mockito.Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void expiredConnectionsAreClosedOnHeartbeat() {
        SseEmitter emitter = org.mockito.Mockito.mock(SseEmitter.class);
        emitters.add(emitter);
        events.connect(new OperationalEventService.Identity(13, "ORGANIZATION_MEMBER", 42L,
                Instant.now().minusSeconds(1)));
        events.heartbeatNow();
        assertEquals(0, events.connectedClientCount());
        verify(emitter).complete();
    }

    @Test
    void rejectsMalformedAudienceAndRestrictsEventDataKeys() {
        assertThrows(IllegalArgumentException.class, () -> events.publish(
                OperationalEventService.EventType.TEST_EVENT,
                OperationalEventService.Audience.USER, 10L, null, Map.of()));
        assertEquals(Map.of("message", "safe"), OperationalEventService.safeData(
                Map.of("message", "safe", "email", "private@example.test", "contactInfo", "private")));
    }

    private SseEmitter subscribe(long userId, String role, Long organizationId) {
        SseEmitter emitter = org.mockito.Mockito.mock(SseEmitter.class);
        emitters.add(emitter);
        events.connect(identity(userId, role, organizationId));
        return emitter;
    }

    private OperationalEventService.Identity identity(long userId, String role, Long organizationId) {
        return new OperationalEventService.Identity(userId, role, organizationId, Instant.now().plusSeconds(300));
    }
}

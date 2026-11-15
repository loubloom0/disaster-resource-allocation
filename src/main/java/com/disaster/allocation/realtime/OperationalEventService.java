package com.disaster.allocation.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** In-memory, role- and tenant-aware transport for operational events. */
@Service
public class OperationalEventService {
    private static final long HEARTBEAT_SECONDS = 20;
    private static final Set<String> SAFE_DATA_KEYS = Set.of(
            "requestId", "resourceId", "resourceName", "category", "quantity",
            "availableQuantity", "status", "priority", "message");

    private final ConcurrentMap<UUID, Client> clients = new ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private final Supplier<SseEmitter> emitterFactory;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "dras-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public OperationalEventService() {
        this(() -> new SseEmitter(0L), true);
    }

    OperationalEventService(Supplier<SseEmitter> emitterFactory) {
        this(emitterFactory, true);
    }

    OperationalEventService(Supplier<SseEmitter> emitterFactory, boolean startHeartbeats) {
        this.emitterFactory = emitterFactory;
        if (startHeartbeats) heartbeats.scheduleWithFixedDelay(this::heartbeatClients, HEARTBEAT_SECONDS,
                HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public SseEmitter connect(Identity identity) {
        SseEmitter emitter = emitterFactory.get();
        UUID id = UUID.randomUUID();
        Client client = new Client(id, identity, emitter);
        clients.put(id, client);
        emitter.onCompletion(() -> clients.remove(id, client));
        emitter.onTimeout(() -> {
            clients.remove(id, client);
            emitter.complete();
        });
        emitter.onError(error -> clients.remove(id, client));
        return emitter;
    }

    /** Publishes an internal event. Callers must choose the narrowest valid audience. */
    public void publish(EventType type, Audience audience, Long organizationId, Long userId,
                        Map<String, ?> data) {
        if (type == null || audience == null) throw new IllegalArgumentException("Event type and audience are required");
        if (audience != Audience.SYSTEM_OPERATORS && audience != Audience.SHARED_RESOURCE_CATALOG
                && organizationId == null) {
            throw new IllegalArgumentException("Organization-scoped events require an organization");
        }
        if (audience == Audience.USER && userId == null) {
            throw new IllegalArgumentException("User-scoped events require a target user");
        }
        EventEnvelope envelope = new EventEnvelope(Long.toString(eventSequence.incrementAndGet()), type,
                Instant.now(), safeData(data));
        clients.values().forEach(client -> {
            if (!mayReceive(client.identity(), audience, organizationId, userId)) return;
            try {
                client.emitter().send(SseEmitter.event().id(envelope.eventId()).name(type.name()).data(envelope));
            } catch (IOException | IllegalStateException error) {
                disconnect(client);
            }
        });
    }

    private static boolean mayReceive(Identity identity, Audience audience, Long organizationId, Long userId) {
        if (identity.globalOperator()) return true;
        return switch (audience) {
            case SYSTEM_OPERATORS -> false;
            case SHARED_RESOURCE_CATALOG -> true;
            case ORGANIZATION_MANAGERS -> organizationId != null && organizationId.equals(identity.organizationId())
                    && "ORGANIZATION_MANAGER".equals(identity.role());
            case USER -> organizationId != null && organizationId.equals(identity.organizationId())
                    && ("ORGANIZATION_MANAGER".equals(identity.role())
                    || ("ORGANIZATION_MEMBER".equals(identity.role()) && userId.equals(identity.userId())));
        };
    }

    private void heartbeatClients() {
        Instant now = Instant.now();
        clients.values().forEach(client -> {
            if (!client.identity().expiresAt().isAfter(now)) {
                disconnect(client);
                return;
            }
            try {
                client.emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException error) {
                disconnect(client);
            }
        });
    }

    private void disconnect(Client client) {
        if (clients.remove(client.id(), client)) client.emitter().complete();
    }

    @PreDestroy
    public void shutdown() {
        heartbeats.shutdownNow();
        clients.values().forEach(this::disconnect);
    }

    int connectedClientCount() { return clients.size(); }

    void heartbeatNow() { heartbeatClients(); }

    public enum EventType {
        TEST_EVENT,
        REQUEST_CREATED,
        REQUEST_STATUS_CHANGED,
        RESOURCE_ALLOCATED,
        RESOURCE_PARTIALLY_RELEASED,
        RESOURCE_RELEASED,
        RESOURCE_AVAILABILITY_CHANGED,
        CRITICAL_ALERT,
        WARNING_ALERT
    }

    public enum Audience { SYSTEM_OPERATORS, SHARED_RESOURCE_CATALOG, ORGANIZATION_MANAGERS, USER }

    public record Identity(long userId, String role, Long organizationId, Instant expiresAt) {
        public Identity {
            if (userId <= 0 || role == null || expiresAt == null) {
                throw new IllegalArgumentException("A valid authenticated identity is required");
            }
            if (!Set.of("SYSTEM_ADMIN", "DISPATCHER", "ORGANIZATION_MANAGER", "ORGANIZATION_MEMBER").contains(role)) {
                throw new IllegalArgumentException("A supported DRAS role is required");
            }
            if (role.startsWith("ORGANIZATION_") && organizationId == null) {
                throw new IllegalArgumentException("Organization identity is required for tenant roles");
            }
        }

        boolean globalOperator() {
            return "SYSTEM_ADMIN".equals(role) || "DISPATCHER".equals(role);
        }
    }

    public record EventEnvelope(String eventId, EventType type, Instant occurredAt, Map<String, Object> data) { }

    private record Client(UUID id, Identity identity, SseEmitter emitter) { }

    static Map<String, Object> safeData(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Map.of();
        var safe = new java.util.LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!SAFE_DATA_KEYS.contains(key) || value == null) return;
            if (value instanceof String text) safe.put(key, text.substring(0, Math.min(text.length(), 200)));
            else if (value instanceof Number || value instanceof Boolean) safe.put(key, value);
        });
        return Map.copyOf(safe);
    }
}

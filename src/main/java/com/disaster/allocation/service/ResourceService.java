package com.disaster.allocation.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.Dto.History;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceInput;
import com.disaster.allocation.repository.ResourceRepository;

@Service
public class ResourceService {

    private final ResourceRepository resources;
    private final AuditService audit;

    public ResourceService(ResourceRepository resources, AuditService audit) {
        this.resources = resources;
        this.audit = audit;
    }

    public List<Resource> list() {
        return resources.findAll();
    }

    public Resource get(long id) {
        return resources.findById(id).orElseThrow(() -> ApiException.notFound("Resource", id));
    }

    @Transactional
    public Resource create(ResourceInput in) {
        Resource r = resources.insert(in);
        resources.addHistory(r.id(), null, "CREATE", r.totalQuantity(), 0, r.availableQuantity(), "Resource created");
        audit.record("RESOURCE_CREATED", "RESOURCE", r.id(), java.util.Map.of("name", r.name(), "quantity", r.totalQuantity()));
        return r;
    }

    /**
     * Update under a row lock. The new total may not drop below what is currently allocated; available
     * is recomputed as total - allocated so total = available + allocated keeps holding.
     */
    @Transactional
    public Resource update(long id, ResourceInput in) {
        Resource cur = resources.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Resource", id));
        if (in.totalQuantity() < cur.allocatedQuantity()) {
            throw ApiException.conflict("Total quantity " + in.totalQuantity() + " is below the "
                    + cur.allocatedQuantity() + " units currently allocated");
        }
        int newAvailable = in.totalQuantity() - cur.allocatedQuantity();
        Resource updated = resources.update(id, in, newAvailable);
        resources.addHistory(id, null, "UPDATE", newAvailable - cur.availableQuantity(), cur.availableQuantity(),
                newAvailable, "Resource edited");
        audit.record("RESOURCE_UPDATED", "RESOURCE", id, java.util.Map.of("name", updated.name(), "quantity", updated.totalQuantity()));
        return updated;
    }

    @Transactional
    public void delete(long id) {
        resources.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Resource", id));
        if (resources.isReferenced(id)) {
            throw ApiException.conflict("Resource is referenced by requests or claims; deactivate it instead");
        }
        resources.deleteHistory(id);
        resources.delete(id);
        audit.record("RESOURCE_DELETED", "RESOURCE", id, java.util.Map.of());
    }

    public List<History> history(Long resourceId, int limit) {
        return resources.history(resourceId, Math.min(Math.max(limit, 1), 500));
    }
}

package ai.kumbuka.memory.tenancy;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * The substrate's tenant resolver: one configured tenant for the whole
 * deployment.
 *
 * <p>This service has no caller surface yet, so there is no request from
 * which a tenant could be derived. What the substrate establishes is the
 * AXIS and its enforcement — the ORM filter, the session setting, the policy
 * — and all three are indifferent to where the value came from. That is why
 * a per-request resolver can be added later without touching any of them,
 * and why adding one now would be building half of a seam whose other half
 * has not been designed.
 *
 * <p>A deployment that serves one tenant is also the honest description of
 * what a single configured value can serve. This resolver never returns null:
 * a missing tenant is a configuration error and fails at startup, because a
 * guessed tenant is the one failure mode row-level security cannot catch —
 * the query is then genuinely well-formed for the wrong tenant.
 */
@ApplicationScoped
public class ConfiguredTenantResolver implements TenantResolver {

    @ConfigProperty(name = "memory.tenant-id")
    UUID tenantId;

    @Override
    public UUID currentTenant() {
        return tenantId;
    }
}

package io.github.cuihairu.redis.streaming.runtime.redis.control;

import lombok.Getter;

/**
 * Control-plane capacity quota applied per tenant (docs/Multi-Tenancy-Design.md, step 3):
 * bounds how many jobs a tenant may register and how much pipeline parallelism it may
 * reserve in total. Enforcement is deterministic — the control plane sums the specs
 * stored for the tenant at submit/upgrade time instead of maintaining a separate
 * Redis counter, so the quota cannot drift from the actual state.
 *
 * <p>A limit of {@code 0} means unlimited for that dimension. The default policy is
 * unlimited everywhere (plain deployments see no behavior change).</p>
 */
@Getter
public final class TenantQuotaPolicy {

    private final int maxJobsPerTenant;
    private final int maxTotalParallelismPerTenant;

    private TenantQuotaPolicy(int maxJobsPerTenant, int maxTotalParallelismPerTenant) {
        this.maxJobsPerTenant = Math.max(0, maxJobsPerTenant);
        this.maxTotalParallelismPerTenant = Math.max(0, maxTotalParallelismPerTenant);
    }

    /** A policy without limits (the default). */
    public static TenantQuotaPolicy none() {
        return new TenantQuotaPolicy(0, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxJobsPerTenant = 0;
        private int maxTotalParallelismPerTenant = 0;

        /** Cap on registered jobs per tenant; 0 = unlimited. */
        public Builder maxJobsPerTenant(int v) {
            this.maxJobsPerTenant = Math.max(0, v);
            return this;
        }

        /** Cap on the sum of spec parallelism per tenant; 0 = unlimited. */
        public Builder maxTotalParallelismPerTenant(int v) {
            this.maxTotalParallelismPerTenant = Math.max(0, v);
            return this;
        }

        public TenantQuotaPolicy build() {
            return new TenantQuotaPolicy(maxJobsPerTenant, maxTotalParallelismPerTenant);
        }
    }
}

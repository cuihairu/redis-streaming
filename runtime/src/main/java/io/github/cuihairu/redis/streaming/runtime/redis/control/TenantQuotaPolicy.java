package io.github.cuihairu.redis.streaming.runtime.redis.control;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Control-plane capacity quota applied per tenant (docs/Multi-Tenancy-Design.md, step 3):
 * bounds how many jobs a tenant may register, how much pipeline parallelism it may
 * reserve in total, and how much declared in-flight capacity ({@code parallelism *
 * maxInFlight} per spec, the consumer memory gate) it may reserve. Enforcement is
 * deterministic — the control plane sums the specs stored for the tenant at
 * submit/upgrade time instead of maintaining a separate Redis counter, so the quota
 * cannot drift from the actual state.
 *
 * <p>A limit of {@code 0} means unlimited for that dimension. The default policy is
 * unlimited everywhere (plain deployments see no behavior change).</p>
 */
@Getter
@EqualsAndHashCode
public final class TenantQuotaPolicy {

    private final int maxJobsPerTenant;
    private final int maxTotalParallelismPerTenant;
    private final int maxTotalInFlightPerTenant;

    private TenantQuotaPolicy(int maxJobsPerTenant, int maxTotalParallelismPerTenant,
                              int maxTotalInFlightPerTenant) {
        this.maxJobsPerTenant = Math.max(0, maxJobsPerTenant);
        this.maxTotalParallelismPerTenant = Math.max(0, maxTotalParallelismPerTenant);
        this.maxTotalInFlightPerTenant = Math.max(0, maxTotalInFlightPerTenant);
    }

    /** A policy without limits (the default). */
    public static TenantQuotaPolicy none() {
        return new TenantQuotaPolicy(0, 0, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxJobsPerTenant = 0;
        private int maxTotalParallelismPerTenant = 0;
        private int maxTotalInFlightPerTenant = 0;

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

        /**
         * Cap on the tenant's declared in-flight capacity, summed as
         * {@code parallelism * maxInFlight} over the stored specs; 0 = unlimited.
         */
        public Builder maxTotalInFlightPerTenant(int v) {
            this.maxTotalInFlightPerTenant = Math.max(0, v);
            return this;
        }

        public TenantQuotaPolicy build() {
            return new TenantQuotaPolicy(maxJobsPerTenant, maxTotalParallelismPerTenant,
                    maxTotalInFlightPerTenant);
        }
    }
}

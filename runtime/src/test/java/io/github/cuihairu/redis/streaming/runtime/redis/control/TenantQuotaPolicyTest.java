package io.github.cuihairu.redis.streaming.runtime.redis.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantQuotaPolicyTest {

    @Test
    void noneMeansUnlimited() {
        TenantQuotaPolicy p = TenantQuotaPolicy.none();
        assertEquals(0, p.getMaxJobsPerTenant());
        assertEquals(0, p.getMaxTotalParallelismPerTenant());
    }

    @Test
    void builderCapsLimits() {
        TenantQuotaPolicy p = TenantQuotaPolicy.builder()
                .maxJobsPerTenant(5)
                .maxTotalParallelismPerTenant(32)
                .build();
        assertEquals(5, p.getMaxJobsPerTenant());
        assertEquals(32, p.getMaxTotalParallelismPerTenant());
    }

    @Test
    void negativeLimitsClampToZero() {
        TenantQuotaPolicy p = TenantQuotaPolicy.builder()
                .maxJobsPerTenant(-3)
                .maxTotalParallelismPerTenant(-1)
                .build();
        assertEquals(0, p.getMaxJobsPerTenant());
        assertEquals(0, p.getMaxTotalParallelismPerTenant());
    }

    @Test
    void builderDefaultsAreUnlimited() {
        TenantQuotaPolicy p = TenantQuotaPolicy.builder().build();
        assertEquals(0, p.getMaxJobsPerTenant());
        assertEquals(0, p.getMaxTotalParallelismPerTenant());
    }
}

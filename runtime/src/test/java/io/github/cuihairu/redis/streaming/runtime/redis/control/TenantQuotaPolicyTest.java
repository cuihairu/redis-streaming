package io.github.cuihairu.redis.streaming.runtime.redis.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantQuotaPolicyTest {

    @Test
    void noneMeansUnlimited() {
        TenantQuotaPolicy p = TenantQuotaPolicy.none();
        assertEquals(0, p.getMaxJobsPerTenant());
        assertEquals(0, p.getMaxTotalParallelismPerTenant());
        assertEquals(0, p.getMaxTotalInFlightPerTenant());
    }

    @Test
    void builderCapsLimits() {
        TenantQuotaPolicy p = TenantQuotaPolicy.builder()
                .maxJobsPerTenant(5)
                .maxTotalParallelismPerTenant(32)
                .maxTotalInFlightPerTenant(1024)
                .build();
        assertEquals(5, p.getMaxJobsPerTenant());
        assertEquals(32, p.getMaxTotalParallelismPerTenant());
        assertEquals(1024, p.getMaxTotalInFlightPerTenant());
    }

    @Test
    void negativeLimitsClampToZero() {
        TenantQuotaPolicy p = TenantQuotaPolicy.builder()
                .maxJobsPerTenant(-3)
                .maxTotalParallelismPerTenant(-1)
                .maxTotalInFlightPerTenant(-8)
                .build();
        assertEquals(0, p.getMaxJobsPerTenant());
        assertEquals(0, p.getMaxTotalParallelismPerTenant());
        assertEquals(0, p.getMaxTotalInFlightPerTenant());
    }

    @Test
    void builderDefaultsAreUnlimited() {
        TenantQuotaPolicy p = TenantQuotaPolicy.builder().build();
        assertEquals(0, p.getMaxJobsPerTenant());
        assertEquals(0, p.getMaxTotalParallelismPerTenant());
        assertEquals(0, p.getMaxTotalInFlightPerTenant());
    }
}

package io.github.cuihairu.redis.streaming.core.utils;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared tenant contract for every module that builds Redis keys.
 *
 * <p>One tenant name must be valid (and equally scoped) in all of them, so the
 * default tenant, the name grammar and the key segment live in one place: the
 * default tenant produces no segment (keys byte-identical to the legacy layout),
 * a non-default tenant inserts a single {@code :tenant} segment right after the
 * key prefix, and names are bounded so derived keys stay unambiguous (no colon).</p>
 */
public final class Tenant {

    /** Default tenant: no key segment, keys byte-identical to the legacy layout. */
    public static final String DEFAULT_TENANT = "default";

    private static final Pattern TENANT_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final String name;

    public Tenant(String name) {
        this.name = normalize(name);
    }

    /** @return the normalized tenant name, never {@code null} */
    public String getName() {
        return name;
    }

    /** @return {@code ""} for the default tenant, else {@code ":" + name} */
    public String segment() {
        return DEFAULT_TENANT.equals(name) ? "" : ":" + name;
    }

    /** @return {@code prefix} followed by this tenant's segment */
    public String scopedPrefix(String prefix) {
        return Tenant.scopedPrefix(prefix, name);
    }

    public boolean isDefault() {
        return DEFAULT_TENANT.equals(name);
    }

    /** @return {@code prefix} followed by the given tenant's segment */
    public static String scopedPrefix(String prefix, String tenant) {
        return prefix + segment(tenant);
    }

    /**
     * Normalize a tenant name: null/blank/{@link #DEFAULT_TENANT} collapse to
     * {@link #DEFAULT_TENANT}; anything else must be 1..64 chars of
     * {@code [A-Za-z0-9._-]} so derived keys stay unambiguous (no colon).
     */
    public static String normalize(String tenant) {
        if (tenant == null || tenant.isBlank() || DEFAULT_TENANT.equals(tenant)) {
            return DEFAULT_TENANT;
        }
        if (!TENANT_PATTERN.matcher(tenant).matches()) {
            throw new IllegalArgumentException(
                    "tenant must match [A-Za-z0-9._-]{1,64} (no colon), got: " + tenant);
        }
        return tenant;
    }

    /** @return the key segment of the given tenant, normalized on the fly */
    public static String segment(String tenant) {
        return new Tenant(tenant).segment();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Tenant other)) {
            return false;
        }
        return name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "Tenant{" + name + "}";
    }
}

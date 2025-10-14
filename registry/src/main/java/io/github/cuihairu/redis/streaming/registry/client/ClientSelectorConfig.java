package io.github.cuihairu.redis.streaming.registry.client;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for ClientSelector fallback behavior.
 */
@Getter
@Setter
public class ClientSelectorConfig {
    // enable fallback if no candidates found for (metadata+metrics)
    private boolean enableFallback = true;

    // fallback steps
    private boolean fallbackDropMetricsFiltersFirst = true;
    private boolean fallbackDropMetadataFiltersNext = true;
    private boolean fallbackUseAllHealthyLast = true;
}


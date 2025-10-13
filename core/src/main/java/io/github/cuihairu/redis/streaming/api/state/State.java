package io.github.cuihairu.redis.streaming.api.state;

import java.io.Serializable;

/**
 * State is the base interface for all managed state.
 */
public interface State extends Serializable {

    /**
     * Clear the state
     */
    void clear();
}

package io.github.cuihairu.redis.streaming.aggregation.incremental;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuantileAggregateFunctionTest {

    @Test
    void singleValueIsItsOwnQuantile() {
        QuantileAggregateFunction<Integer> f = new QuantileAggregateFunction<>(0.95);
        QuantileAggregateFunction.QuantileAccumulator<Integer> acc = f.createAccumulator();
        acc = f.add(7, acc);
        assertEquals(7.0, f.getResult(acc));
    }

    @Test
    void nearestRankMatchesQuantileAnalyzerDefinition() {
        QuantileAggregateFunction<Integer> f = new QuantileAggregateFunction<>(0.5);
        QuantileAggregateFunction.QuantileAccumulator<Integer> acc = f.createAccumulator();
        for (int v : new int[]{40, 10, 30, 20}) {
            acc = f.add(v, acc);
        }
        // floor(0.5 * (4 - 1)) = 1 -> second smallest of {10,20,30,40}
        assertEquals(20.0, f.getResult(acc));
    }

    @Test
    void quantileOneIsTheMax() {
        QuantileAggregateFunction<Integer> f = new QuantileAggregateFunction<>(1.0);
        QuantileAggregateFunction.QuantileAccumulator<Integer> acc = f.createAccumulator();
        for (int v : new int[]{5, 9, 1}) {
            acc = f.add(v, acc);
        }
        assertEquals(9.0, f.getResult(acc));
    }

    @Test
    void emptyWindowYieldsNull() {
        QuantileAggregateFunction<Integer> f = new QuantileAggregateFunction<>(0.9);
        assertNull(f.getResult(f.createAccumulator()));
    }

    @Test
    void mergeConcatenatesValuesBeforeRanking() {
        QuantileAggregateFunction<Integer> f = new QuantileAggregateFunction<>(0.9);
        QuantileAggregateFunction.QuantileAccumulator<Integer> left = f.createAccumulator();
        QuantileAggregateFunction.QuantileAccumulator<Integer> right = f.createAccumulator();
        left = f.add(1, left);
        left = f.add(2, left);
        right = f.add(3, right);
        right = f.add(100, right);
        // floor(0.9 * (4 - 1)) = 2 -> third smallest of {1,2,3,100}
        assertEquals(3.0, f.getResult(f.merge(left, right)));
    }

    @Test
    void rejectsQuantilesOutsideUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new QuantileAggregateFunction<>(0.0));
        assertThrows(IllegalArgumentException.class, () -> new QuantileAggregateFunction<>(-0.5));
        assertThrows(IllegalArgumentException.class, () -> new QuantileAggregateFunction<>(1.0001));
        assertEquals(0.5, new QuantileAggregateFunction<Double>(0.5).getQuantile());
    }

    @Test
    void rejectsNullValueAndSurvivesSerialization() throws Exception {
        QuantileAggregateFunction<Integer> f = new QuantileAggregateFunction<>(0.5);
        QuantileAggregateFunction.QuantileAccumulator<Integer> fresh = f.createAccumulator();
        assertThrows(NullPointerException.class, () -> f.add(null, fresh));
        QuantileAggregateFunction.QuantileAccumulator<Integer> acc = f.add(4, fresh);
        acc = f.add(6, acc);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(f);
            out.writeObject(acc);
        }
        QuantileAggregateFunction<Integer> restoredFunction;
        QuantileAggregateFunction.QuantileAccumulator<Integer> restoredAccumulator;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restoredFunction = (QuantileAggregateFunction<Integer>) in.readObject();
            restoredAccumulator = (QuantileAggregateFunction.QuantileAccumulator<Integer>) in.readObject();
        }
        assertEquals(4.0, restoredFunction.getResult(restoredAccumulator));
    }
}

package io.github.cuihairu.redis.streaming.aggregation.incremental;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class UVAggregateFunctionTest {

    @Test
    void countsDistinctElementsOnly() {
        UVAggregateFunction<String> f = new UVAggregateFunction<>();
        UVAggregateFunction.DistinctAccumulator<String> acc = f.createAccumulator();
        assertEquals(0L, f.getResult(acc));
        for (String v : new String[]{"u1", "u2", "u1", "u3", "u2"}) {
            acc = f.add(v, acc);
        }
        assertEquals(3L, f.getResult(acc));
    }

    @Test
    void mergeUnionsDistinctSets() {
        UVAggregateFunction<String> f = new UVAggregateFunction<>();
        UVAggregateFunction.DistinctAccumulator<String> left = f.createAccumulator();
        UVAggregateFunction.DistinctAccumulator<String> right = f.createAccumulator();
        left = f.add("u1", left);
        left = f.add("u2", left);
        right = f.add("u2", right);
        right = f.add("u3", right);
        assertEquals(3L, f.getResult(f.merge(left, right)));
    }

    @Test
    void rejectsNullValueAndSurvivesSerialization() throws Exception {
        UVAggregateFunction<String> f = new UVAggregateFunction<>();
        assertThrows(NullPointerException.class, () -> f.add(null, f.createAccumulator()));
        UVAggregateFunction.DistinctAccumulator<String> acc = f.add("u1", f.createAccumulator());
        acc = f.add("u1", acc);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(f);
            out.writeObject(acc);
        }
        UVAggregateFunction<String> restoredFunction;
        UVAggregateFunction.DistinctAccumulator<String> restoredAccumulator;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restoredFunction = (UVAggregateFunction<String>) in.readObject();
            restoredAccumulator = (UVAggregateFunction.DistinctAccumulator<String>) in.readObject();
        }
        assertEquals(1L, restoredFunction.getResult(restoredAccumulator));
    }
}

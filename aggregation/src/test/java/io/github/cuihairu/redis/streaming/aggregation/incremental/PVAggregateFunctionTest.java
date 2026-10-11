package io.github.cuihairu.redis.streaming.aggregation.incremental;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PVAggregateFunctionTest {

    @Test
    void countsEveryRecord() {
        PVAggregateFunction<String> f = new PVAggregateFunction<>();
        PVAggregateFunction.CountAccumulator<String> acc = f.createAccumulator();
        assertEquals(0L, f.getResult(acc));
        for (int i = 0; i < 5; i++) {
            acc = f.add("page", acc);
        }
        assertEquals(5L, f.getResult(acc));
    }

    @Test
    void countsRepeatedValuesWithoutDeduplication() {
        PVAggregateFunction<Integer> f = new PVAggregateFunction<>();
        PVAggregateFunction.CountAccumulator<Integer> acc = f.createAccumulator();
        for (int v : new int[]{1, 1, 1, 2}) {
            acc = f.add(v, acc);
        }
        assertEquals(4L, f.getResult(acc));
    }

    @Test
    void mergeSumsCounts() {
        PVAggregateFunction<String> f = new PVAggregateFunction<>();
        PVAggregateFunction.CountAccumulator<String> left = f.createAccumulator();
        PVAggregateFunction.CountAccumulator<String> right = f.createAccumulator();
        left = f.add("p", left);
        left = f.add("p", left);
        right = f.add("p", right);
        assertEquals(3L, f.getResult(f.merge(left, right)));
    }

    @Test
    void rejectsNullValueAndSurvivesSerialization() throws Exception {
        PVAggregateFunction<String> f = new PVAggregateFunction<>();
        assertThrows(NullPointerException.class, () -> f.add(null, f.createAccumulator()));
        PVAggregateFunction.CountAccumulator<String> acc = f.add("p", f.createAccumulator());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(f);
            out.writeObject(acc);
        }
        PVAggregateFunction<String> restoredFunction;
        PVAggregateFunction.CountAccumulator<String> restoredAccumulator;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restoredFunction = (PVAggregateFunction<String>) in.readObject();
            restoredAccumulator = (PVAggregateFunction.CountAccumulator<String>) in.readObject();
        }
        assertEquals(1L, restoredFunction.getResult(restoredAccumulator));
    }
}

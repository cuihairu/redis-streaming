# Table

Module: `table/`

Stream-Table duality (materialized views).

## Scope
- KTable 抽象：将 key-based updates 解释为“表”的当前状态（物化视图）
- 当前实现提供 In-Memory 与 Redis-backed 两种 KTable

## Concepts
- `KTable<K,V>`：每个 key 在任意时刻最多一个 value（value 为 null 视为删除）
- `toStream()`：当前实现以 snapshot 方式导出（用于 runtime/examples）

## Minimal Sample
```java
import io.github.cuihairu.redis.streaming.table.impl.InMemoryKTable;

InMemoryKTable<String, Integer> table = new InMemoryKTable<>();
table.put("a", 1);
table.put("b", 2);

table.toStream()
        .map(kv -> kv.getKey() + "=" + kv.getValue())
        .print("table> ");
```

## References
- Design.md

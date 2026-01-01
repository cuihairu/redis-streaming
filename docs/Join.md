# Join

Module: `join/`

Stream joins.

## Scope
- 基于时间窗口的 stream-stream join（当前为 in-memory joiner，适用于测试/简单用例）
- 支持 `INNER/LEFT/RIGHT/FULL_OUTER`（部分 join 类型的输出时机见 `StreamJoiner` 实现）

## 2. Key Classes
- `JoinFunction`, `JoinWindow`, `JoinType`, `StreamJoiner`

## Minimal Sample
```java
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import io.github.cuihairu.redis.streaming.join.StreamJoiner;

var cfg = JoinConfig.innerJoin(
        (String s) -> s, // left key
        (String s) -> s, // right key
        JoinWindow.ofSize(java.time.Duration.ofSeconds(5))
);

StreamJoiner<String, String, String, String> joiner =
        new StreamJoiner<>(cfg, (l, r) -> l + "+" + r);

joiner.processRight("k1");
var out = joiner.processLeft("k1"); // ["k1+k1"]
```

## References
- Design.md

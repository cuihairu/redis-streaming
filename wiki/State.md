# State Management

Module: `state/`

Distributed state primitives: Value/Map/List/Set state and backends.

## 1. Scope
- 进程内与分布式状态抽象，配合 `core/api.state` 使用（占位）

## 2. Key Classes (placeholder)
- `state.backend.StateBackend`（接口）及实现（TBD）

## 3. Minimal Sample (placeholder)
```java
ValueState<Integer> counter = ...;
Integer v = counter.value();
counter.update(v == null ? 1 : v+1);
```

## References
- Design.md

# Streaming Runtime Module

## Overview

This module provides a simple, in-memory runtime implementation of the Streaming API defined in the `core` module.

## Current Status

**Status**: Minimal in-memory runtime available (single-threaded)

The runtime module provides a small, pull-based in-memory implementation intended for tests/examples:

- `StreamExecutionEnvironment`: `fromElements`, `fromCollection`, `addSource`
- `DataStream`: `map`, `filter`, `flatMap`, `keyBy`, `addSink`, `print`
- `KeyedStream`: `process`, `reduce`, `getState` (keyed `ValueState`)

Not yet implemented in the in-memory runtime:
- `window(...)`, `sum(...)`
- timers / watermark propagation / checkpointing

## Why a Separate Runtime Module?

The `core` module defines the Stream Processing API interfaces, while `runtime` provides an actual execution engine. This separation:

1. **Avoids Circular Dependencies**: The `core` module is depended on by `state`, `watermark`, and `window` modules. If runtime code were in `core`, it would create circular dependencies.

2. **Modular Design**: Users can use just the API definitions from `core` without pulling in the full runtime implementation.

3. **Alternative Runtimes**: In the future, different runtime implementations could be provided (e.g., distributed runtime, optimized runtime).

## Alternative: Use Existing Modules Directly

While the unified streaming runtime is under development, you can use the individual modules directly:

- **MQ Module**: For message queue operations
- **State Module**: For state management
- **Window + Aggregation**: For windowed computations
- **CEP Module**: For complex event processing

See the `examples` module for usage patterns.

## Planned Features

- [x] Core operator abstractions
- [x] Pull-based execution model (in-memory)
- [x] Basic keyed state (in-memory `ValueState`)
- [ ] Window assignment and triggering
- [ ] Watermark handling
- [ ] Checkpointing integration
- [ ] Parallel execution

## Quick Example

```java
var env = StreamExecutionEnvironment.getExecutionEnvironment();
env.fromElements("a b", "c")
    .flatMap(line -> Arrays.asList(line.split(" ")))
    .keyBy(w -> w)
    .reduce((x, y) -> x)
    .print("word=");
```

## Implementation Notes

The runtime implementation faces several challenges:

1. **Watermark API Complexity**: The WatermarkGenerator interface requires WatermarkOutput callbacks, which need careful integration with the execution model.

2. **Generic Type Inference**: Java's type system and Lombok's code generation can conflict in complex generic hierarchies.

3. **Window Semantics**: Proper window triggering requires watermark coordination across parallel streams.

Given these complexities, the initial focus is on getting individual modules working correctly. A complete streaming runtime similar to Apache Flink's is a significant undertaking requiring thousands of lines of carefully designed code.

## Recommendation

For production use cases:
- Use individual modules (mq, state, aggregation, cep) directly
- For complex stream processing, consider Apache Flink or similar mature frameworks
- This framework excels at lightweight, Redis-backed streaming operations

## Future Work

The runtime engine will be implemented incrementally:

**Phase 1** (Current): API definitions in `core`
**Phase 2** (Planned): Simple in-memory runtime for testing
**Phase 3** (Future): Production-grade runtime with full feature support

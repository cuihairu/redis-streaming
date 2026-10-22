# CEP Module

Module: `cep/`

## Module Responsibility

Complex Event Processing: **predicate matching** (a single condition) and **sequence matching** (multi-step, with quantifiers/contiguity/time constraints) over events pushed one by one, returning the matched event sequences. Pure Java (Lombok-generated boilerplate), depends on core (`api project(':core')` in `build.gradle`), **uses no Redis**, and embeds no execution engine — the caller invokes `process(event, timestamp)` inside its own message loop.

8 main source files, package root `io.github.cuihairu.redis.streaming.cep`:

| Class | Role |
|---|---|
| `Pattern<T>` | the functional match-condition (predicate) interface, combinable with `and`/`or`/`negate` |
| `PatternBuilder<T>` | the condition combiner: `any()` / `all()` / `build()`, and can produce a `PatternConfig` |
| `PatternConfig<T>` | the single-condition matching configuration (time window, contiguity, sequence length, event reuse) |
| `PatternMatcher<T>` | the single-condition matcher (optionally maintaining a bounded internal extension sequences) |
| `PatternSequence<T>` | the multi-step sequence (`begin/where/next/followedBy/followedByAny` + quantifiers + `within`) |
| `PatternSequenceMatcher<T>` | the multi-step sequence matcher (the Kleene closure, three contiguities, time constraints) |
| `PatternQuantifier` | the quantifiers: `{n}`, `+`, `*`, `?`, `{n,}`, `{min,max}` |
| `EventSequence<T>` | the event-sequence value object (start/end/duration) |

## Pattern and PatternBuilder

```java
// Pattern.of: build from a predicate; and/or/negate combine
Pattern<Integer> p = Pattern.of(x -> x > 5);
Pattern<Integer> combined = p.and(Pattern.of(x -> x < 10))
                             .or(Pattern.of(x -> x == 0))
                             .negate();
combined.matches(20);

// PatternBuilder: combine conditions into one (each event is evaluated independently; no sequences involved)
Pattern<Integer> all = PatternBuilder.<Integer>create()
        .where(Pattern.of(x -> x > 0))
        .where(Pattern.of(x -> x < 10))
        .all();                       // matches only when all conditions hold; any() matches when any holds; build() == all()

// PatternBuilder directly produces a PatternConfig (from PatternBuilderTest)
PatternConfig<Integer> config = PatternBuilder.<Integer>create()
        .where(Pattern.of(x -> x > 5))
        .withTimeWindow(Duration.ofMinutes(5));           // contiguous = false
PatternConfig<Integer> strict = PatternBuilder.<Integer>create()
        .where(Pattern.of(x -> x > 5))
        .withContiguousTimeWindow(Duration.ofSeconds(30)); // contiguous = true
```

- `PatternBuilder.where(p)` and `followedBy(p)` are equivalent in the single-condition combiner (both just append a condition).
- With an empty condition list, `any()` is always false and `all()`/`build()` always true (source behavior).

## PatternConfig (the single-condition configuration)

Lombok `@Data` + `@Builder`, `Serializable`:

| Field | Type | Default | Notes |
|---|---|---|---|
| `pattern` | `Pattern<T>` | none (required) | the match condition |
| `timeWindow` | `Duration` | `Duration.ofMinutes(1)` | the matching time window |
| `contiguous` | `boolean` | `false` | require contiguity: a non-matching event clears the tracked sequences |
| `maxSequenceLength` | `int` | `100` | the maximum events per sequence; must be positive |
| `allowEventReuse` | `boolean` | `false` | a matching event both opens a new sequence and extends existing ones |

`validate()`: throws `IllegalArgumentException` when `pattern == null`, `timeWindow` is null/zero/negative, or `maxSequenceLength <= 0`. `getTimeWindowMillis()` returns the millisecond value.

## PatternMatcher (the single-condition matcher)

```java
PatternConfig<Integer> config = PatternConfig.<Integer>builder()
        .pattern(Pattern.of(x -> x > 5))
        .timeWindow(Duration.ofSeconds(10))
        .build();
PatternMatcher<Integer> matcher = new PatternMatcher<>(config);          // the active-sequence cap defaults to 1000
PatternMatcher<Integer> capped = new PatternMatcher<>(config, 100);      // explicit maxActiveSequences

List<EventSequence<Integer>> matches = matcher.process(10);              // uses the current time
List<EventSequence<Integer>> hits = matcher.process(10, 1000L);          // explicit timestamp
matcher.getActiveSequenceCount();
matcher.clear();
matcher.getConfig();
```

Behavior notes (source semantics):

- The constructor validates `config.validate()` first; a negative `maxActiveSequences` throws `IllegalArgumentException`, and `0` disables extension-sequence tracking entirely (completed output is unaffected). The default cap is `PatternMatcher.DEFAULT_MAX_ACTIVE_SEQUENCES = 1000`, with the oldest extension sequences evicted newest-first when exceeded (B-10: uncapped, the partial-sequence count under `allowEventReuse` grows exponentially with the event count).
- **`process` returns single-event sequences**: every matching event immediately yields a length-1 `EventSequence`. With `allowEventReuse(true)` a matching event additionally extends the internal active sequences (serving the `contiguous`/expiry-cleanup state checks), but those multi-event sequences are **never** emitted as completed results — for multi-event sequence matching use `PatternSequenceMatcher`.
- With `contiguous=true`, one non-matching event clears all active sequences (`PatternMatcherTest#testContiguousBreaksOnNonMatchingEvent`).
- Active sequences expire against `timeWindow` (`now - startTime > timeWindowMillis`).

## EventSequence (the sequence value object)

```java
EventSequence<String> seq = new EventSequence<>(List.of("a", "b"), 1000L, 2500L);
seq.getEvents();      // List.of("a","b") (copied at construction)
seq.getFirst();       // "a"; null for an empty sequence
seq.getLast();        // "b"; null for an empty sequence
seq.size();           // 2
seq.isEmpty();
seq.getDuration();    // endTime - startTime = 1500
seq.getEventsCopy();  // a defensive copy
seq.addEvent("c");    // appends (start/end unaffected)
```

Two more constructors exist, `new EventSequence<>()` / `new EventSequence<>(List<T>)` (start/end take the current millis). Lombok `@Data` provides `getEvents()` / `getStartTime()` / `getEndTime()`.

## PatternQuantifier (the quantifiers)

Static factories (illegal arguments throw `IllegalArgumentException`):

| Factory | Semantics | Constraint |
|---|---|---|
| `exactly(int n)` | `{n}` exactly n times | `n >= 1` |
| `oneOrMore()` | `+` one or more | — |
| `zeroOrMore()` | `*` zero or more | — |
| `optional()` | `?` zero or one | — |
| `times(int min, int max)` | the `{min,max}` range | `min >= 0` and `max >= min` |
| `atLeast(int n)` | `{n,}` at least n times | `n >= 0` |

```java
PatternQuantifier q = PatternQuantifier.times(2, 4);
q.matches(2);              // true; matches(occurrences) asks whether the count falls in [min, max]
q.getType();               // QuantifierType.RANGE
q.getMinOccurrences();     // 2
q.getMaxOccurrences();     // 4
q.toString();              // "{2,4}"; the per-type symbols: {n} / + / * / ? / {n,} / {min,max}
```

The `QuantifierType` enum: `EXACTLY` / `ONE_OR_MORE` / `ZERO_OR_MORE` / `OPTIONAL` / `AT_LEAST` / `RANGE`.

## PatternSequence (the multi-step definition)

```java
PatternSequence<String> pattern = PatternSequence.<String>begin()      // or begin("first", pattern)
        .where("A", Pattern.of(s -> s.equals("A")))      // a RELAXED step (usable as the first step)
        .oneOrMore()                                     // the quantifier applies to the last step
        .followedBy("B", Pattern.of(s -> s.equals("B"))) // RELAXED: other events may appear in between
        .within(Duration.ofSeconds(5));                  // the time constraint over the whole sequence
```

Step methods and contiguity semantics (`next`/`followedBy`/`followedByAny` cannot be the first step and throw `IllegalStateException`):

| Method | `ContiguityType` | Semantics |
|---|---|---|
| `where(name, pattern)` | `RELAXED` | appends a condition (usable as the first step) |
| `next(name, pattern)` | `STRICT` | strict contiguity: no events allowed in between |
| `followedBy(name, pattern)` | `RELAXED` | loose contiguity: other events may appear in between |
| `followedByAny(name, pattern)` | `NON_DETERMINISTIC` | nondeterministic loose: the same event may serve multiple matches |

Quantifier methods (applied to the last step): `times(PatternQuantifier)`, `times(int)` (= exactly n), `times(int min, int max)`, `oneOrMore()`, `zeroOrMore()`, `optional()`. A step's default quantifier is `exactly(1)`.

Other notes:

- `within(Duration)`: a null/zero/negative window throws `IllegalArgumentException`.
- `validate()`: throws `IllegalStateException` with no steps.
- `size()` returns the step count; `getSteps()` / `getTimeWindow()` come from Lombok `@Getter`; `isContiguous()` is currently always `false` — the class-level `contiguous` flag has no setter, and contiguity is expressed entirely per step via `ContiguityType`.
- `PatternSequence.PatternStep`: `getName()` / `getPattern()` / `getContiguityType()` / `getQuantifier()` / `setQuantifier(...)`; `toString()` looks like `A+ [RELAXED]`.
- `toString()` looks like `PatternSequence[A+ [RELAXED] -> B [RELAXED]] within PT5S`.

## PatternSequenceMatcher (the multi-step matcher)

```java
PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);            // the retention cap defaults to 1000
PatternSequenceMatcher<String> bounded = new PatternSequenceMatcher<>(pattern, 200);       // maxRetainedMatches

List<PatternSequenceMatcher.CompleteMatch<String>> newMatches = matcher.process("A", 1000L); // each call returns the newly completed matches
List<PatternSequenceMatcher.CompleteMatch<String>> history = matcher.getCompleteMatches();   // oldest first, at most maxRetainedMatches entries
matcher.getPartialMatchCount();
matcher.clear();
```

Behavior notes (source semantics):

- The constructor runs `patternSequence.validate()`; a negative `maxRetainedMatches` throws `IllegalArgumentException`, and `0` keeps no history (`process` still returns each match to the caller). The default is `PatternSequenceMatcher.DEFAULT_MAX_RETAINED_MATCHES = 1000` (B-20: unbounded history grows without limit with runtime).
- Single-step quantifiers drive on `minOccurrences`/`maxOccurrences`: below min the matcher stays on the step collecting events; at min it advances; past max it stops extending.
- An event already consumed by a partial (successfully extending it) is not used to seed a new match; only unconsumed events seed new ones.
- A `STRICT` step: a non-matching event drops that partial (the chain breaks); `RELAXED`/`NON_DETERMINISTIC` steps: a non-matching event keeps the partial and skips the event; a `NON_DETERMINISTIC` step additionally retains a copy of the original partial after consuming (the same event may match again).
- With `within` set, partials expire at `startTimestamp + timeWindow`; without it, no expiry cleanup.
- `getCompleteMatches()` returns a copy, oldest first, at most `maxRetainedMatches` entries.

`CompleteMatch<E>`:

```java
match.getEvents();                    // all events (a copy)
match.getEventsByStep();              // Map<Integer, List<E>> (by step index)
match.getStartTimestamp(); match.getEndTimestamp();
match.getDuration();                  // end - start (ms)
match.getEventsForStep("login", sequence);  // events by step name; an unknown name returns an empty list
```

## Configuration

The CEP module has no configuration-file keys; every parameter is a Builder/constructor argument. Defaults summarized:

| Location | Parameter | Default |
|---|---|---|
| `PatternConfig.Builder` | `timeWindow` | `Duration.ofMinutes(1)` |
| `PatternConfig.Builder` | `contiguous` | `false` |
| `PatternConfig.Builder` | `maxSequenceLength` | `100` |
| `PatternConfig.Builder` | `allowEventReuse` | `false` |
| the `PatternMatcher` constructor | `maxActiveSequences` | `PatternMatcher.DEFAULT_MAX_ACTIVE_SEQUENCES` = `1000` |
| the `PatternSequenceMatcher` constructor | `maxRetainedMatches` | `PatternSequenceMatcher.DEFAULT_MAX_RETAINED_MATCHES` = `1000` |
| `PatternSequence.PatternStep` | `quantifier` | `PatternQuantifier.exactly(1)` |

## Usage Examples

### Single-condition matching (from PatternMatcherTest)

```java
Pattern<Integer> greaterThan5 = Pattern.of(x -> x > 5);
PatternConfig<Integer> config = PatternConfig.<Integer>builder()
        .pattern(greaterThan5)
        .timeWindow(Duration.ofSeconds(10))
        .build();

PatternMatcher<Integer> matcher = new PatternMatcher<>(config);
List<EventSequence<Integer>> matches = matcher.process(10);  // 1 entry (a single-event sequence)
assertEquals(10, matches.get(0).getFirst());
```

### The Kleene closure quantifiers (from AdvancedCEPTest)

```java
// A* B: zero or more A followed by B (zeroOrMore; a direct B also matches — see testKleeneStar_ZeroOrMore)
PatternSequence<String> pattern = PatternSequence.<String>begin()
        .where("A", Pattern.of(s -> s.equals("A")))
        .zeroOrMore()
        .followedBy("B", Pattern.of(s -> s.equals("B")));
PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

matcher.process("A", 2000L);
List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("B", 4000L);
```

The other quantifiers are isomorphic: `.oneOrMore()` (`A+`), `.times(3)` (`A{3}`), `.times(2, 4)` (`A{2,4}`), `.optional()` (`B?`).

### Failed-login detection: 3+ failures within 1 minute (from AdvancedCEPTest)

```java
PatternSequence<LoginEvent> pattern = PatternSequence.<LoginEvent>begin()
        .where("FAILED_LOGIN", Pattern.of(e -> e.status.equals("FAILED")))
        .times(3, Integer.MAX_VALUE)
        .within(Duration.ofMinutes(1));
PatternSequenceMatcher<LoginEvent> matcher = new PatternSequenceMatcher<>(pattern);

matcher.process(new LoginEvent("user1", "FAILED"), 1000L);
matcher.process(new LoginEvent("user1", "FAILED"), 2000L);
List<PatternSequenceMatcher.CompleteMatch<LoginEvent>> matches =
        matcher.process(new LoginEvent("user1", "FAILED"), 3000L);  // the 3rd failure produces the match
```

(`LoginEvent` is a static nested class in `AdvancedCEPTest` with the two fields `userId`/`status`.)

### The time constraint (from AdvancedCEPTest#testWithinTimeConstraint)

```java
PatternSequence<String> pattern = PatternSequence.<String>begin()
        .where("A", Pattern.of(s -> s.equals("A")))
        .followedBy("B", Pattern.of(s -> s.equals("B")))
        .within(Duration.ofSeconds(5));
PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

matcher.process("A", 0L);
matcher.process("B", 3000L);  // inside the window → 1 match
matcher.clear();
matcher.process("A", 0L);
matcher.process("B", 6000L);  // past the window → 0 matches
```

### Strict contiguity and per-step retrieval (from PatternSequenceMatcherCoverageTest)

```java
PatternSequence<String> sequence = PatternSequence.<String>begin("login", type("a"))
        .next("browse", type("b"));        // STRICT: b must directly follow a
PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(sequence);

matcher.process("a", 10L);
List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("b", 20L);

matches.get(0).getEventsForStep("login", sequence);   // List.of("a")
matches.get(0).getEventsForStep("browse", sequence);  // List.of("b")
matches.get(0).getDuration();                          // 10
```

## Operator Form: Running Sequence Matching in a DataStream Pipeline

`PatternSequenceProcessFunction` (in the `cep.operator` package) wraps `PatternSequenceMatcher` into a `KeyedProcessFunction` — one independent matcher instance per key, so sequence matching hangs directly off a pipeline (design details in [the Join/CEP operator design](Join-CEP-Operators-Design.md)):

```java
env.fromMqTopic(eventsTopic, "cep-group")
    .map(m -> parseEvent(String.valueOf(m.getPayload())))
    .keyBy(Event::getUserId)
    .process(new PatternSequenceProcessFunction<>(pattern, Event::getTimestamp))
    .addSink(out);   // every completed sequence yields one EventSequence
```

- Per-key independent matching state, no cross-talk; the `within` deadline is handled by the matcher's event-driven cleanup (the operator registers no engine timers);
- The key cap `maxTrackedKeys` (default 10000, 0 = unbounded): over the cap, idle keys (no in-flight partials) are dropped first, then the least-recently-touched keys are evicted, preventing unbounded growth under high-cardinality keys; eviction only loses in-flight partials — completed matches are unaffected;
- **From Phase 2 (2026-10-12) partials live in keyed state (a write-through cache)**: the in-instance matcher is the hot cache; after each event the key's keyed state is written through (cleared when there are no partials), and on a cache miss the state hydrates before matching continues; keys evicted by `maxTrackedKeys` are not resurrected from state with stale partials within this instance. After failover, unfinished sequences are no longer lost (the miss surface narrows) and the restored partials complete their matches;
- Partials' snapshot/restore ride `PatternSequenceMatcher.exportPartialMatches()` / `restorePartialMatches()` (the flat event order is restored by concatenating per-step sequences in ascending step order; pinned by a dedicated test).

## Related Documentation

- [Architecture](Architecture.md) - the module's place in the overall architecture
- [window](window.md) - windows and triggers (another time-dimension way of cutting event streams)
- [The Join/CEP operator design](Join-CEP-Operators-Design.md) - the operator-form input model and the phased roadmap

# CEP Pattern Matching - Known Issues

## Overview
The CEP (Complex Event Processing) module has advanced pattern matching features that are partially implemented. Some advanced quantifier patterns need fixes.

## Working Features
- ✅ Basic pattern sequences (A → B → C)
- ✅ oneOrMore quantifier (A+)
- ✅ Exact quantifiers (A{3})
- ✅ Relaxed contiguity (followedBy)
- ✅ Time constraints (within)

## Issues to Fix

### 1. Optional Pattern (?)
**Status**: ✅ Fixed

**Pattern**: `A B? C` (A, optionally B, then C)

**Expected**: Should match both `A,C` and `A,B,C`

**Notes**: Optional steps are skipped on-demand when the current event does not match the optional pattern, allowing the event to match subsequent steps without creating duplicate matches.

### 2. ZeroOrMore at Start (*)
**Status**: ✅ Fixed

**Pattern**: `A* B` (zero or more A, followed by B)

### 3. Strict Contiguity (next)
**Status**: ✅ Fixed

**Pattern**: `A next B` (B must immediately follow A, no events in between)

**Expected**:
- `A,B` → Match
- `A,C,B` → No match

**Notes**: Strict steps now fail immediately on the first non-matching event while waiting for that step.

### 4. Range Quantifiers
**Status**: ✅ Fixed

**Pattern**: `A{2,4}` (2 to 4 A's)

**Expected**: Should match on 2nd, 3rd, and 4th A, but not 5th

**Notes**: Range quantifiers now emit matches at valid counts without starting overlapping new matches from events already used to extend existing matches (default non-reuse behavior).

## Test Files
- `cep/src/test/java/io/github/cuihairu/redis/streaming/cep/AdvancedCEPTest.java`

## Implementation Notes
- Core logic lives in `cep/src/main/java/io/github/cuihairu/redis/streaming/cep/PatternSequenceMatcher.java`.
- Advanced quantifier/contiguity cases are covered by `AdvancedCEPTest`.

## Technical Notes

### Pattern Matching Flow
1. New event arrives
2. Try to extend existing partial matches
3. Try to start new matches from this event
4. Clean up expired matches
5. Return completed matches

### Key Classes
- `PatternSequence` - Defines the pattern structure
- `PatternSequenceMatcher` - Matches events against pattern
- `PatternQuantifier` - Defines occurrence constraints (*, +, ?, {n}, {n,m})
- `PartialMatch` - Tracks in-progress matches

### Current tryExtend() Logic
```java
if (event matches current step) {
    add event to current step
    if (count >= min && count <= max) {
        create branch to stay on current step (if < max)
        create branch to move to next step
    }
} else {
    if (count >= min) {
        skip current step, try next step
    }
    if (contiguity == RELAXED) {
        keep partial alive
    }
}
```

## Priority
**Medium** - Basic patterns work, these are advanced features

## Related Files
- `cep/src/main/java/io/github/cuihairu/redis/streaming/cep/PatternSequenceMatcher.java`
- `cep/src/test/java/io/github/cuihairu/redis/streaming/cep/AdvancedCEPTest.java`

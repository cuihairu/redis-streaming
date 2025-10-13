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
**Status**: ❌ Not working correctly

**Pattern**: `A B? C` (A, optionally B, then C)

**Expected**: Should match both `A,C` and `A,B,C`

**Current Behavior**: Does not correctly skip optional patterns

**Root Cause**: In `tryExtend()`, when current step has `minOccurrences=0` and event doesn't match, it should:
1. Skip current step (increment currentStep)
2. Try matching event against next step
3. If next step matches and is last, mark as complete

**Problem**: The partial match's `currentStep` is not advancing correctly after matching first step.

### 2. ZeroOrMore at Start (*)
**Status**: ✅ Partially fixed

**Pattern**: `A* B` (zero or more A, followed by B)

**Current**: Works when B is processed directly (matches with zero A's)

**Remaining Issue**: May not work correctly with longer sequences

### 3. Strict Contiguity (next)
**Status**: ❌ Not working correctly

**Pattern**: `A next B` (B must immediately follow A, no events in between)

**Expected**:
- `A,B` → Match
- `A,C,B` → No match

**Current Behavior**: Not properly failing when non-matching events appear between strict patterns

**Root Cause**: The strict contiguity check needs to happen at the point where we advance to the next step, not just when we process non-matching events.

### 4. Range Quantifiers
**Status**: ❌ Not working correctly

**Pattern**: `A{2,4}` (2 to 4 A's)

**Expected**: Should match on 2nd, 3rd, and 4th A, but not 5th

**Current**: Logic for tracking multiple matches at different counts may be incomplete

## Test Files
- `AdvancedCEPTest.java` - Contains all failing tests
- Tests are tagged but not excluded

## Recommended Fix Approach

### Phase 1: Fix Optional Pattern
1. Debug why `currentStep` doesn't advance after first match
2. Ensure copy() method properly copies currentStep
3. Fix logic for advancing currentStep when matching intermediate steps

### Phase 2: Fix Strict Contiguity
1. Track the "last event" for each partial match
2. When advancing to a STRICT step, verify no events were skipped
3. Fail the match if events were skipped for strict step

### Phase 3: Fix Range Quantifiers
1. Implement proper tracking of "how many matches to generate"
2. When in range `{2,4}`, should generate matches at 2, 3, and 4 occurrences
3. Test with overlapping matches

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
- `/cep/src/main/java/io/github/cuihairu/streaming/cep/PatternSequenceMatcher.java`
- `/cep/src/test/java/io/github/cuihairu/streaming/cep/AdvancedCEPTest.java`

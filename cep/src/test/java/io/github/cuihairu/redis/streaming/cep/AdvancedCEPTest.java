package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for advanced CEP features including Kleene closures and complex patterns
 *
 * NOTE: Some tests are disabled due to known issues with advanced quantifiers.
 * See /cep/CEP_ISSUES.md for details.
 */
class AdvancedCEPTest {

    @Test
    void testKleenePlus_OneOrMore() {
        // Pattern: A+  (one or more A)
        PatternSequence<String> pattern = PatternSequence.<String>begin()
            .where("A", Pattern.of(s -> s.equals("A")))
            .oneOrMore();

        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        // Send events: A, A, A, B
        matcher.process("A", 1000);
        matcher.process("A", 2000);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("A", 3000);

        // Should match sequences: A, AA, AAA
        assertTrue(matches.size() > 0);
    }

    @Test
    void testKleeneStar_ZeroOrMore() {
        // Pattern: A* B (zero or more A, followed by B)
        PatternSequence<String> pattern = PatternSequence.<String>begin()
            .where("A", Pattern.of(s -> s.equals("A")))
            .zeroOrMore()
            .followedBy("B", Pattern.of(s -> s.equals("B")));

        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        // Test 1: B only (zero A's)
        List<PatternSequenceMatcher.CompleteMatch<String>> matches1 = matcher.process("B", 1000);
        assertEquals(1, matches1.size());

        // Test 2: A, A, B
        matcher.clear();
        matcher.process("A", 2000);
        matcher.process("A", 3000);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches2 = matcher.process("B", 4000);
        assertTrue(matches2.size() > 0);
    }

    @Test
    void testOptionalPattern() {
        // Pattern: A B? C (A, optionally B, then C)
        PatternSequence<String> pattern = PatternSequence.<String>begin()
            .where("A", Pattern.of(s -> s.equals("A")))
            .followedBy("B", Pattern.of(s -> s.equals("B")))
            .optional()
            .followedBy("C", Pattern.of(s -> s.equals("C")));

        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        // Test 1: A, C (without B)
        matcher.process("A", 1000);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches1 = matcher.process("C", 2000);
        assertEquals(1, matches1.size());

        // Test 2: A, B, C (with B)
        matcher.clear();
        matcher.process("A", 3000);
        matcher.process("B", 4000);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches2 = matcher.process("C", 5000);
        assertEquals(1, matches2.size());
    }

    @Test
    void testExactQuantifier() {
        // Pattern: A{3} (exactly 3 A's)
        PatternSequence<String> pattern = PatternSequence.<String>begin()
            .where("A", Pattern.of(s -> s.equals("A")))
            .times(3);

        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        // Should only match after exactly 3 A's
        matcher.process("A", 1000);
        matcher.process("A", 2000);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("A", 3000);

        assertEquals(1, matches.size());
    }

    @Test
    void testRangeQuantifier() {
        // Pattern: A{2,4} (2 to 4 A's)
        PatternSequence<String> pattern = PatternSequence.<String>begin()
            .where("A", Pattern.of(s -> s.equals("A")))
            .times(2, 4);

        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        matcher.process("A", 1000);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches1 = matcher.process("A", 2000);
        assertEquals(1, matches1.size()); // 2 A's

        List<PatternSequenceMatcher.CompleteMatch<String>> matches2 = matcher.process("A", 3000);
        assertEquals(1, matches2.size()); // 3 A's

        List<PatternSequenceMatcher.CompleteMatch<String>> matches3 = matcher.process("A", 4000);
        assertEquals(1, matches3.size()); // 4 A's

        List<PatternSequenceMatcher.CompleteMatch<String>> matches4 = matcher.process("A", 5000);
        assertEquals(0, matches4.size()); // 5 A's - exceeds max
    }

    @Test
    void testFollowedBy_RelaxedContiguity() {
        // Pattern: A followedBy B (relaxed - allows events in between)
        PatternSequence<String> pattern = PatternSequence.<String>begin()
            .where("A", Pattern.of(s -> s.equals("A")))
            .followedBy("B", Pattern.of(s -> s.equals("B")));

        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        // A, C, D, B (C and D in between)
        matcher.process("A", 1000);
        matcher.process("C", 2000);
        matcher.process("D", 3000);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("B", 4000);

        assertEquals(1, matches.size());
    }

    @Test
    void testNext_StrictContiguity() {
        // Pattern: A next B (strict - no events allowed in between)
        PatternSequence<String> pattern = PatternSequence.<String>begin()
            .where("A", Pattern.of(s -> s.equals("A")))
            .next("B", Pattern.of(s -> s.equals("B")));

        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        // Test 1: A, C, B (should fail - C in between)
        matcher.process("A", 1000);
        matcher.process("C", 2000);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches1 = matcher.process("B", 3000);
        assertEquals(0, matches1.size());

        // Test 2: A, B (should succeed - strict contiguity)
        matcher.clear();
        matcher.process("A", 4000);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches2 = matcher.process("B", 5000);
        assertEquals(1, matches2.size());
    }

    @Test
    void testWithinTimeConstraint() {
        // Pattern: A followedBy B within 5 seconds
        PatternSequence<String> pattern = PatternSequence.<String>begin()
            .where("A", Pattern.of(s -> s.equals("A")))
            .followedBy("B", Pattern.of(s -> s.equals("B")))
            .within(Duration.ofSeconds(5));

        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        // Test 1: A at t=0, B at t=3s (within window)
        matcher.process("A", 0);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches1 = matcher.process("B", 3000);
        assertEquals(1, matches1.size());

        // Test 2: A at t=0, B at t=6s (outside window)
        matcher.clear();
        matcher.process("A", 0);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches2 = matcher.process("B", 6000);
        assertEquals(0, matches2.size()); // Should not create new match
    }

    @Test
    void testComplexPattern_LoginFailureDetection() {
        // Pattern: Detect 3 or more failed logins within 1 minute
        PatternSequence<LoginEvent> pattern = PatternSequence.<LoginEvent>begin()
            .where("FAILED_LOGIN", Pattern.of(e -> e.status.equals("FAILED")))
            .times(3, Integer.MAX_VALUE)
            .within(Duration.ofMinutes(1));

        PatternSequenceMatcher<LoginEvent> matcher = new PatternSequenceMatcher<>(pattern);

        // Simulate failed login attempts
        LoginEvent failed1 = new LoginEvent("user1", "FAILED");
        LoginEvent failed2 = new LoginEvent("user1", "FAILED");
        LoginEvent failed3 = new LoginEvent("user1", "FAILED");

        matcher.process(failed1, 1000);
        matcher.process(failed2, 2000);
        List<PatternSequenceMatcher.CompleteMatch<LoginEvent>> matches = matcher.process(failed3, 3000);

        assertTrue(matches.size() > 0);
    }

    @Test
    void testComplexPattern_TransactionFraudDetection() {
        // Pattern: Small transaction followed by large transaction within 10 seconds
        PatternSequence<Transaction> pattern = PatternSequence.<Transaction>begin()
            .where("SMALL", Pattern.of(t -> t.amount < 10))
            .followedBy("LARGE", Pattern.of(t -> t.amount > 1000))
            .within(Duration.ofSeconds(10));

        PatternSequenceMatcher<Transaction> matcher = new PatternSequenceMatcher<>(pattern);

        Transaction small = new Transaction(5.0);
        Transaction large = new Transaction(2000.0);

        matcher.process(small, 1000);
        List<PatternSequenceMatcher.CompleteMatch<Transaction>> matches = matcher.process(large, 3000);

        assertEquals(1, matches.size());
        assertEquals(2000, matches.get(0).getDuration()); // 3000 - 1000
    }

    // Helper classes for tests
    static class LoginEvent {
        String userId;
        String status;

        LoginEvent(String userId, String status) {
            this.userId = userId;
            this.status = status;
        }
    }

    static class Transaction {
        double amount;

        Transaction(double amount) {
            this.amount = amount;
        }
    }
}

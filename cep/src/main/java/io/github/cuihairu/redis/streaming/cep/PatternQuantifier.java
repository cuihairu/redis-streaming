package io.github.cuihairu.redis.streaming.cep;

import java.io.Serializable;

/**
 * Quantifier for pattern matching - supports Kleene closure operations.
 * Defines how many times a pattern should match in a sequence.
 */
public class PatternQuantifier implements Serializable {

    private static final long serialVersionUID = 1L;

    private final QuantifierType type;
    private final int minOccurrences;
    private final int maxOccurrences;

    private PatternQuantifier(QuantifierType type, int minOccurrences, int maxOccurrences) {
        this.type = type;
        this.minOccurrences = minOccurrences;
        this.maxOccurrences = maxOccurrences;
    }

    /**
     * Create an EXACTLY quantifier - matches exactly n times
     */
    public static PatternQuantifier exactly(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Exactly quantifier requires n >= 1");
        }
        return new PatternQuantifier(QuantifierType.EXACTLY, n, n);
    }

    /**
     * Create a ONE_OR_MORE quantifier - matches 1 or more times (Kleene plus: +)
     */
    public static PatternQuantifier oneOrMore() {
        return new PatternQuantifier(QuantifierType.ONE_OR_MORE, 1, Integer.MAX_VALUE);
    }

    /**
     * Create a ZERO_OR_MORE quantifier - matches 0 or more times (Kleene star: *)
     */
    public static PatternQuantifier zeroOrMore() {
        return new PatternQuantifier(QuantifierType.ZERO_OR_MORE, 0, Integer.MAX_VALUE);
    }

    /**
     * Create an OPTIONAL quantifier - matches 0 or 1 times (?)
     */
    public static PatternQuantifier optional() {
        return new PatternQuantifier(QuantifierType.OPTIONAL, 0, 1);
    }

    /**
     * Create a RANGE quantifier - matches between min and max times
     */
    public static PatternQuantifier times(int min, int max) {
        if (min < 0) {
            throw new IllegalArgumentException("Min occurrences must be >= 0");
        }
        if (max < min) {
            throw new IllegalArgumentException("Max occurrences must be >= min");
        }
        return new PatternQuantifier(QuantifierType.RANGE, min, max);
    }

    /**
     * Create an AT_LEAST quantifier - matches at least n times
     */
    public static PatternQuantifier atLeast(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("At least quantifier requires n >= 0");
        }
        return new PatternQuantifier(QuantifierType.AT_LEAST, n, Integer.MAX_VALUE);
    }

    /**
     * Check if the given occurrence count satisfies this quantifier
     */
    public boolean matches(int occurrences) {
        return occurrences >= minOccurrences && occurrences <= maxOccurrences;
    }

    public QuantifierType getType() {
        return type;
    }

    public int getMinOccurrences() {
        return minOccurrences;
    }

    public int getMaxOccurrences() {
        return maxOccurrences;
    }

    @Override
    public String toString() {
        return switch (type) {
            case EXACTLY -> "{" + minOccurrences + "}";
            case ONE_OR_MORE -> "+";
            case ZERO_OR_MORE -> "*";
            case OPTIONAL -> "?";
            case AT_LEAST -> "{" + minOccurrences + ",}";
            case RANGE -> "{" + minOccurrences + "," + maxOccurrences + "}";
        };
    }

    public enum QuantifierType {
        EXACTLY,       // {n}
        ONE_OR_MORE,   // +
        ZERO_OR_MORE,  // *
        OPTIONAL,      // ?
        AT_LEAST,      // {n,}
        RANGE          // {min,max}
    }
}

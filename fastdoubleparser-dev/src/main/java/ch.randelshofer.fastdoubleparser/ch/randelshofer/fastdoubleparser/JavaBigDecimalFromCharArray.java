/*
 * @(#)JavaBigDecimalFromCharArray.java
 * Copyright © 2022 Werner Randelshofer, Switzerland. MIT License.
 */
package ch.randelshofer.fastdoubleparser;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.RecursiveTask;

import static ch.randelshofer.fastdoubleparser.FastIntegerMath.computePowerOfTen;
import static ch.randelshofer.fastdoubleparser.FastIntegerMath.createPowersOfTenFloor16Map;
import static ch.randelshofer.fastdoubleparser.FastIntegerMath.fillPowersOfTenFloor16Recursive;
import static ch.randelshofer.fastdoubleparser.FastIntegerMath.parallelMultiply;
import static ch.randelshofer.fastdoubleparser.FastIntegerMath.splitFloor16;


/**
 * Parses a {@code double} from a {@code byte} array.
 */
final class JavaBigDecimalFromCharArray extends AbstractNumberParser {
    /**
     * Threshold on the number of digits for selecting the
     * recursive algorithm instead of the iterative algorithm.
     * <p>
     * Set this to {@link Integer#MAX_VALUE} if you only want to use the
     * iterative algorithm.
     * <p>
     * Set this to {@code 0} if you only want to use the recursive algorithm.
     * <p>
     * Rationale for choosing a specific threshold value:
     * The iterative algorithm has a smaller constant overhead than the
     * recursive algorithm. We speculate that we break even somewhere at twice
     * the threshold value.
     */
    private static final int RECURSION_THRESHOLD = 128;
    /**
     * Threshold on the number of digits for selecting the multi-threaded
     * algorithm instead of the single-thread algorithm.
     * <p>
     * Set this to {@link Integer#MAX_VALUE} if you only want to use
     * the single-threaded algorithm.
     * <p>
     * Set this to {@code 0} if you only want to use the multi-threaded
     * algorithm.
     * <p>
     * Rationale for choosing a specific threshold value:
     * We speculate that we need to perform at least 10,000 CPU cycles
     * before it is worth using multiple threads.
     */
    private static final int DEFAULT_PARALLEL_THRESHOLD = 1024;
    /**
     * Threshold on the number of input characters for selecting the
     * algorithm optimised for few digits in the significand vs. the algorithm for many
     * digits in the significand.
     * <p>
     * Set this to {@link Integer#MAX_VALUE} if you only want to use
     * the algorithm optimised for few digits in the significand.
     * <p>
     * Set this to {@code 0} if you only want to use the algorithm for
     * long inputs.
     * <p>
     * Rationale for choosing a specific threshold value:
     * We speculate that we only need to use the algorithm for large inputs
     * if there is zero chance, that we can parse the input with the algorithm
     * for small inputs.
     * <pre>
     * optional significant sign = 1
     * 18 significant digits = 18
     * optional decimal point in significant = 1
     * optional exponent = 1
     * optional exponent sign = 1
     * 10 exponent digits = 10
     * </pre>
     */
    private static final int MANY_DIGITS_THRESHOLD = 1 + 18 + 1 + 1 + 1 + 10;
    /**
     * See {@link JavaBigDecimalParser}.
     */
    private final static int MAX_DIGIT_COUNT = 1_292_782_621;
    private final static long MAX_EXPONENT_NUMBER = Integer.MAX_VALUE;
    private final static BigInteger TEN_POW_16 = BigInteger.valueOf(10_000_000_000_000_000L);

    /**
     * Creates a new instance.
     */
    public JavaBigDecimalFromCharArray() {

    }

    /**
     * Parses digits in exponential time O(e^n).
     */
    static BigInteger parseDigitsIterative(char[] str, int from, int to) {
        int numDigits = to - from;
        BigSignificand bigSignificand = new BigSignificand(FastIntegerMath.estimateNumBits(numDigits));
        int preroll = from + (numDigits & 7);
        bigSignificand.add(FastDoubleSwar.parseUpTo7Digits(str, from, preroll));
        for (from = preroll; from < to; from += 8) {
            bigSignificand.fma(100_000_000, FastDoubleSwar.parseEightDigits(str, from));
        }

        return bigSignificand.toBigInteger();
    }

    /**
     * Parses digits in exponential time O(e^n).
     */
    private static BigInteger parseDigitsRecursive(char[] str, int from, int to, Map<Integer, BigInteger> powersOfTen) {
        // Base case: All sequences of 18 or fewer digits fit into a long.
        int numDigits = to - from;
        if (numDigits <= 18) {
            return parseDigitsUpTo18(str, from, to);
        }
        if (numDigits <= RECURSION_THRESHOLD) {
            return parseDigitsIterative(str, from, to);
        }

        // Recursion case: Sequences of more than 18 digits do not fit into a long.
        int mid = splitFloor16(from, to);
        BigInteger high = parseDigitsRecursive(str, from, mid, powersOfTen);
        BigInteger low = parseDigitsRecursive(str, mid, to, powersOfTen);

        high = high.multiply(powersOfTen.get(to - mid));
        return low.add(high);
    }

    /**
     * Parses up to 18 digits in exponential time O(e^n).
     */
    private static BigInteger parseDigitsUpTo18(char[] str, int from, int to) {
        int numDigits = to - from;
        int preroll = from + (numDigits & 7);
        long significand = FastDoubleSwar.parseUpTo7Digits(str, from, preroll);
        for (from = preroll; from < to; from += 8) {
            significand = significand * 100_000_000L + FastDoubleSwar.parseEightDigits(str, from);
        }
        return BigInteger.valueOf(significand);
    }

    private boolean isDigit(char c) {
        return '0' <= c && c <= '9';
    }

    /**
     * Parses a {@code BigDecimalString} as specified in {@link JavaBigDecimalParser}.
     *
     * @param str      the input string
     * @param offset   start of the input data
     * @param length   length of the input data
     * @param parallel
     * @return the parsed {@link BigDecimal}
     * @throws NullPointerException     if str is null
     * @throws IllegalArgumentException if offset or length are illegal
     * @throws NumberFormatException    if the input string can not be parsed successfully
     */
    public BigDecimal parseBigDecimalString(char[] str, int offset, int length, boolean parallel) {
        int parallelThreshold = parallel ? DEFAULT_PARALLEL_THRESHOLD : Integer.MAX_VALUE;
        if (length >= MANY_DIGITS_THRESHOLD) {
            return parseBigDecimalStringWithManyDigits(str, offset, length, parallelThreshold);
        }
        long significand = 0L;
        final int integerPartIndex;
        int decimalPointIndex = -1;
        final int exponentIndicatorIndex;

        final int endIndex = offset + length;
        int index = offset;
        char ch = index < endIndex ? str[index] : 0;
        boolean illegal = false;


        // Parse optional sign
        // -------------------
        final boolean isNegative = ch == '-';
        if (isNegative || ch == '+') {
            ch = ++index < endIndex ? str[index] : 0;
            if (ch == 0) {
                throw new NumberFormatException(SYNTAX_ERROR);
            }
        }

        // Parse significand
        integerPartIndex = index;
        for (; index < endIndex; index++) {
            ch = str[index];
            if (isDigit(ch)) {
                // This might overflow, we deal with it later.
                significand = 10 * (significand) + ch - '0';
            } else if (ch == '.') {
                illegal |= decimalPointIndex >= 0;
                decimalPointIndex = index;
                for (; index < endIndex - 4; index += 4) {
                    int digits = FastDoubleSwar.tryToParseFourDigits(str, index + 1);
                    if (digits < 0) {
                        break;
                    }
                    // This might overflow, we deal with it later.
                    significand = 10_000L * significand + digits;
                }
            } else {
                break;
            }
        }

        final int digitCount;
        final int significandEndIndex = index;
        long exponent;
        if (decimalPointIndex < 0) {
            digitCount = significandEndIndex - integerPartIndex;
            decimalPointIndex = significandEndIndex;
            exponent = 0;
        } else {
            digitCount = significandEndIndex - integerPartIndex - 1;
            exponent = decimalPointIndex - significandEndIndex + 1;
        }

        // Parse exponent number
        // ---------------------
        long expNumber = 0;
        if (ch == 'e' || ch == 'E') {
            exponentIndicatorIndex = index;
            ch = ++index < endIndex ? str[index] : 0;
            boolean isExponentNegative = ch == '-';
            if (isExponentNegative || ch == '+') {
                ch = ++index < endIndex ? str[index] : 0;
            }
            illegal |= !isDigit(ch);
            do {
                // Guard against overflow
                if (expNumber < MAX_EXPONENT_NUMBER) {
                    expNumber = 10 * (expNumber) + ch - '0';
                }
                ch = ++index < endIndex ? str[index] : 0;
            } while (isDigit(ch));
            if (isExponentNegative) {
                expNumber = -expNumber;
            }
            exponent += expNumber;
        } else {
            exponentIndicatorIndex = endIndex;
        }
        if (illegal || index < endIndex
                || digitCount == 0
                || exponent < Integer.MIN_VALUE
                || exponent > Integer.MAX_VALUE
                || digitCount > MAX_DIGIT_COUNT) {
            throw new NumberFormatException(SYNTAX_ERROR);
        }

        if (digitCount <= 18) {
            return new BigDecimal(isNegative ? -significand : significand).scaleByPowerOfTen((int) exponent);
        }
        return valueOfBigDecimalString(str, integerPartIndex, decimalPointIndex, decimalPointIndex + 1, exponentIndicatorIndex, isNegative, (int) exponent, parallelThreshold);
    }

    /**
     * Parses a big decimal string that has many digits.
     */
    private BigDecimal parseBigDecimalStringWithManyDigits(char[] str, int offset, int length, int parallelThreshold) {
        if (length > MAX_INPUT_LENGTH) {
            throw new NumberFormatException(SYNTAX_ERROR);
        }
        final int nonZeroIntegerPartIndex;
        final int integerPartIndex;
        int nonZeroFractionalPartIndex = -1;
        int decimalPointIndex = -1;
        final int exponentIndicatorIndex;

        final int endIndex = offset + length;
        int index = offset;
        char ch = index < endIndex ? str[index] : 0;
        boolean illegal = false;

        // Parse optional sign
        // -------------------
        final boolean isNegative = ch == '-';
        if (isNegative || ch == '+') {
            ch = ++index < endIndex ? str[index] : 0;
            if (ch == 0) {
                throw new NumberFormatException(SYNTAX_ERROR);
            }
        }

        // Count digits of significand
        // -----------------
        integerPartIndex = index;
        int swarLimit = Math.min(endIndex - 8, 1 << 30);
        while (index < swarLimit && FastDoubleSwar.isEightZeroes(str, index)) {
            index += 8;
        }
        while (index < endIndex && str[index] == '0') {
            index++;
        }
        // Count digits of integer part
        nonZeroIntegerPartIndex = index;
        while (index < swarLimit && FastDoubleSwar.isEightDigits(str, index)) {
            index += 8;
        }
        while (index < endIndex && isDigit(ch = str[index])) {
            index++;
        }
        if (ch == '.') {
            decimalPointIndex = index++;
            // skip leading zeroes
            while (index < swarLimit && FastDoubleSwar.isEightZeroes(str, index)) {
                index += 8;
            }
            while (index < endIndex && str[index] == '0') {
                index++;
            }
            nonZeroFractionalPartIndex = index;
            // Count digits of fraction part
            while (index < swarLimit && FastDoubleSwar.isEightDigits(str, index)) {
                index += 8;
            }
            while (index < endIndex && isDigit(ch = str[index])) {
                index++;
            }
        }

        final int digitCount;
        final int significandEndIndex = index;
        long exponent;
        if (decimalPointIndex < 0) {
            digitCount = significandEndIndex - nonZeroIntegerPartIndex;
            decimalPointIndex = significandEndIndex;
            nonZeroFractionalPartIndex = significandEndIndex;
            exponent = 0;
        } else {
            digitCount = nonZeroIntegerPartIndex == decimalPointIndex
                    ? significandEndIndex - nonZeroFractionalPartIndex
                    : significandEndIndex - nonZeroIntegerPartIndex - 1;
            exponent = decimalPointIndex - significandEndIndex + 1;
        }

        // Parse exponent number
        // ---------------------
        long expNumber = 0;
        if (ch == 'e' || ch == 'E') {
            exponentIndicatorIndex = index;
            ch = ++index < endIndex ? str[index] : 0;
            boolean isExponentNegative = ch == '-';
            if (isExponentNegative || ch == '+') {
                ch = ++index < endIndex ? str[index] : 0;
            }
            illegal = !isDigit(ch);
            do {
                // Guard against overflow
                if (expNumber < MAX_EXPONENT_NUMBER) {
                    expNumber = 10 * (expNumber) + ch - '0';
                }
                ch = ++index < endIndex ? str[index] : 0;
            } while (isDigit(ch));
            if (isExponentNegative) {
                expNumber = -expNumber;
            }
            exponent += expNumber;
        } else {
            exponentIndicatorIndex = endIndex;
        }
        if (illegal || index < endIndex) {
            throw new NumberFormatException(SYNTAX_ERROR);
        }
        if (exponentIndicatorIndex - integerPartIndex == 0) {
            throw new NumberFormatException(SYNTAX_ERROR);
        }
        if (exponent < Integer.MIN_VALUE
                || exponent > Integer.MAX_VALUE
                || digitCount > MAX_DIGIT_COUNT) {
            throw new NumberFormatException(VALUE_EXCEEDS_LIMITS);
        }
        return valueOfBigDecimalString(str, nonZeroIntegerPartIndex, decimalPointIndex, nonZeroFractionalPartIndex, exponentIndicatorIndex, isNegative, (int) exponent, parallelThreshold);
    }

    private BigInteger parseDigits(char[] str, int from, int to, Map<Integer, BigInteger> powersOfTen, int parallelThreshold) {
        int numDigits = to - from;
        if (numDigits < RECURSION_THRESHOLD) {
            return parseDigitsIterative(str, from, to);
        } else if (numDigits < parallelThreshold) {
            return parseDigitsRecursive(str, from, to, powersOfTen);
        } else {
            return new ParseDigitsTask(str, from, to, powersOfTen, parallelThreshold).compute();
        }
    }

    private BigDecimal valueOfBigDecimalString(char[] str, int integerPartIndex, int decimalPointIndex, int nonZeroFractionalPartIndex, int exponentIndicatorIndex, boolean isNegative, int exponent, int parallelThreshold) {
        int integerExponent = exponentIndicatorIndex - decimalPointIndex - 1;
        int fractionDigitsCount = exponentIndicatorIndex - nonZeroFractionalPartIndex;
        int integerDigitsCount = decimalPointIndex - integerPartIndex;
        NavigableMap<Integer, BigInteger> powersOfTen = null;
        BigInteger integerPart;
        boolean parallel = parallelThreshold < Integer.MAX_VALUE;
        if (integerDigitsCount > 0) {
            if (integerDigitsCount > RECURSION_THRESHOLD) {
                powersOfTen = createPowersOfTenFloor16Map();
                fillPowersOfTenFloor16Recursive(powersOfTen, integerPartIndex, decimalPointIndex, parallel);
                integerPart = parseDigits(str, integerPartIndex, decimalPointIndex, powersOfTen, parallelThreshold);
            } else {
                integerPart = parseDigits(str, integerPartIndex, decimalPointIndex, null, parallelThreshold);
            }
        } else {
            integerPart = BigInteger.ZERO;
        }

        BigInteger significand;
        if (fractionDigitsCount > 0) {
            BigInteger fractionalPart;
            if (fractionDigitsCount > RECURSION_THRESHOLD) {
                if (powersOfTen == null) {
                    powersOfTen = createPowersOfTenFloor16Map();
                }
                fillPowersOfTenFloor16Recursive(powersOfTen, decimalPointIndex + 1, exponentIndicatorIndex, parallel);
                fractionalPart = parseDigits(str, decimalPointIndex + 1, exponentIndicatorIndex, powersOfTen, parallelThreshold);
            } else {
                fractionalPart = parseDigits(str, decimalPointIndex + 1, exponentIndicatorIndex, null, parallelThreshold);
            }
            if (integerPart.signum() == 0) {
                significand = fractionalPart;
            } else {
                BigInteger integerFactor = computePowerOfTen(powersOfTen, integerExponent, parallel);
                significand = FastIntegerMath.parallelMultiply(integerPart, integerFactor, parallel).add(fractionalPart);
            }
        } else {
            significand = integerPart;
        }

        BigDecimal result = new BigDecimal(significand, -exponent);
        return isNegative ? result.negate() : result;
    }

    /**
     * Parses digits in exponential time O(e^n).
     */
    static class ParseDigitsTask extends RecursiveTask<BigInteger> {
        private final int from, to;
        private final char[] str;
        private final Map<Integer, BigInteger> powersOfTen;
        private final int parallelThreshold;

        ParseDigitsTask(char[] str, int from, int to, Map<Integer, BigInteger> powersOfTen, int parallelThreshold) {
            this.from = from;
            this.to = to;
            this.str = str;
            this.powersOfTen = powersOfTen;
            this.parallelThreshold = parallelThreshold;
        }

        protected BigInteger compute() {
            int range = to - from;
            // Base case:
            if (range <= parallelThreshold) {
                return parseDigitsRecursive(str, from, to, powersOfTen);
            }
            // Recursion case:
            int mid = splitFloor16(from, to);
            JavaBigIntegerFromCharArray.ParseDigitsTask high = new JavaBigIntegerFromCharArray.ParseDigitsTask(str, from, mid, powersOfTen, parallelThreshold);
            JavaBigIntegerFromCharArray.ParseDigitsTask low = new JavaBigIntegerFromCharArray.ParseDigitsTask(str, mid, to, powersOfTen, parallelThreshold);
            // perform about half the work locally
            low.fork();
            BigInteger highValue = parallelMultiply(high.compute(), powersOfTen.get(to - mid), true);
            return low.join().add(highValue);
        }
    }
}
/*
 * @(#)BigSignificand.java
 * Copyright © 2022 Werner Randelshofer, Switzerland. MIT License.
 */
package ch.randelshofer.fastdoubleparser;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * A mutable significand with a fixed number of bits.
 */
class BigSignificand {
    private final int[] x;
    private int firstNonZeroInt;
    private static final long LONG_MASK = 0xffffffffL;
    private final int numInts;

    public BigSignificand(long numBits) {
        if (numBits <= 0 || numBits >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("numBits=" + numBits);
        }
        int numLongs = (int) ((numBits + 63) >>> 6) + 1;
        numInts = numLongs << 1;
        x = new int[numInts];
        firstNonZeroInt = numInts;
    }

    /**
     * Multiplies the significand with the specified value in place.
     *
     * @param value the multiplication factor, must be a non-negative value
     * @throws ArrayIndexOutOfBoundsException on overflow
     */
    public void mul(int value) {
        long factor = value & LONG_MASK;
        long carry = 0;
        int i = numInts - 1;
        for (; i >= firstNonZeroInt; i--) {
            long product = factor * (x(i) & LONG_MASK) + carry;
            x(i, (int) product);
            carry = product >>> 32;
        }
        if (carry != 0) {
            x(i, (int) carry);
            firstNonZeroInt = i;
        }
    }

    /**
     * Multiplies the significand with the specified factor in place,
     * and then adds the specified addend to it (also in place).
     *
     * @param factor the multiplication factor, must be a non-negative value
     * @param addend the addend, must be a non-negative value
     * @throws ArrayIndexOutOfBoundsException on overflow
     */
    public void fma(int factor, int addend) {
        long factorL = factor & LONG_MASK;
        long carry = addend;
        int i = numInts - 1;
        for (; i >= firstNonZeroInt; i--) {
            long product = factorL * (x(i) & LONG_MASK) + carry;
            x(i, (int) product);
            carry = product >>> 32;
        }
        if (carry != 0) {
            x(i, (int) carry);
            firstNonZeroInt = i;
        }
    }

    /**
     * Adds the specified value {@code addend * 10^addendExponent}
     * to this value.
     *
     * @param addend         the addend
     * @param addendExponent the exponent of the addend
     */
    public void add(int addend, int addendExponent) {
        throw new UnsupportedOperationException();
    }

    private int x(int i) {
        return x[i];
    }

    private void x(int i, int value) {
        x[i] = value;
    }


    /**
     * Adds the specified value to the significand in place.
     *
     * @param value the addend, must be a non-negative value
     * @throws ArrayIndexOutOfBoundsException on overflow
     */
    public void add(int value) {
        if (value == 0) {
            return;
        }
        long carry = value & LONG_MASK;
        int i = numInts - 1;
        for (; carry != 0; i--) {
            long sum = (x(i) & LONG_MASK) + carry;
            x(i, (int) sum);
            carry = sum >>> 32;
        }
        firstNonZeroInt = Math.min(firstNonZeroInt, i + 1);
    }

    @Override
    public String toString() {
        return toBigInteger().toString();
    }

    public BigInteger toBigInteger() {
        byte[] bytes = new byte[x.length << 2];
        IntBuffer buf = ByteBuffer.wrap(bytes).asIntBuffer();
        for (int i = 0; i < x.length; i++) {
            buf.put(i, x[i]);
        }
        return new BigInteger(bytes);
    }

    public static long estimateNumBits(long numDecimalDigits) {
        // For the decimal number 10 we need log_2(10) = 3.3219 bits.
        // The following formula uses 3.322 * 1024 = 3401.8 rounded up
        // and adds 1, so that we overestimate but never underestimate
        // the number of bits.
        return (((numDecimalDigits * 3402L) >>> 10) + 1);
    }

}

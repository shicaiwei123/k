package org.kframework.utils;

import java.util.stream.IntStream;

public class BitSet implements Cloneable {
    int oneValue = -1;
    java.util.BitSet severalValues = null;
    int count = 0;

//    java.util.BitSet oracle = new java.util.BitSet();

    void checkOracle() {
//        java.util.BitSet x = new java.util.BitSet();
//        this.stream().forEach(x::set);
//        if (!x.equals(oracle)) {
//            throw new AssertionError("bum");
//        }
    }

    public IntStream stream() {
        if (count == 0)
            return IntStream.empty();
        else if (count == 1)
            return IntStream.of(oneValue);
        else
            return severalValues.stream();
    }

    public int nextSetBit(int i) {
        if (count == 0)
            return -1;
        else if (count == 1)
            return i > oneValue ? -1 : oneValue;
        else
            return severalValues.nextSetBit(i);
    }

    public void or(BitSet that) {
//        oracle.or(that.oracle);

        if (that.count == 0) {
            checkOracle();
            return;
        }

        if (count == 1) {
            if (that.count == 1 && oneValue == that.oneValue) {
                checkOracle();
                return;
            }
            severalValues = new java.util.BitSet();
            severalValues.set(oneValue);
        } else if (count == 0) {
            if (that.count == 1) {
                oneValue = that.oneValue;
                count = 1;
                checkOracle();
                return;
            }
            severalValues = new java.util.BitSet();
        }

        if (that.count == 1)
            severalValues.set(that.oneValue);
        else
            severalValues.or(that.severalValues);

        count = 2;
        checkOracle();
    }

    public void and(BitSet that) {
//        oracle.and(that.oracle);

        if (this.count == 0) {
            checkOracle();
            return;
        }

        if (that.count == 0) {
            this.oneValue = -1;
            this.count = 0;
            this.severalValues = null;
            checkOracle();
            return;
        }

        if (that.count == 1) {
            if (this.count == 1) {
                if (this.oneValue == that.oneValue) {
                    checkOracle();
                    return;
                } else {
                    this.oneValue = -1;
                    this.count = 0;
                    checkOracle();
                    return;
                }
            } else {
                if (severalValues.get(that.oneValue)) {
                    this.oneValue = that.oneValue;
                    this.severalValues = null;
                    this.count = 1;
                    checkOracle();
                    return;
                } else {
                    this.oneValue = -1;
                    this.severalValues = null;
                    this.count = 0;
                    checkOracle();
                    return;
                }
            }
        } else {
            if (this.count == 1) {
                if (that.severalValues.get(this.oneValue)) {
                    checkOracle();
                    return;
                } else {
                    count = 0;
                    oneValue = -1;
                    checkOracle();
                    return;
                }
            } else {
                severalValues.and(that.severalValues);
            }
        }
        int firstBit = severalValues.nextSetBit(0);
        if (firstBit == -1) {
            severalValues = null;
            count = 0;
        } else if (severalValues.nextSetBit(firstBit + 1) == -1) {
            severalValues = null;
            count = 1;
            oneValue = firstBit;
        }
        checkOracle();
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public void set(int b) {
//        oracle.set(b);

        if (count == 0) {
            count = 1;
            oneValue = b;
            checkOracle();
            return;
        } else if (count == 1) {
            if (oneValue == b) {
                checkOracle();
                return;
            }
            severalValues = new java.util.BitSet();
            severalValues.set(oneValue);
            oneValue = -1;
        }
        severalValues.set(b);
        count = 2;
        checkOracle();
    }

    @Override
    public BitSet clone() {
        BitSet bitSet = new BitSet();
        if (this.count == 1) {
            bitSet.oneValue = oneValue;
        } else if (count > 1) {
            bitSet.severalValues = (java.util.BitSet) severalValues.clone();
        }
        bitSet.count = count;
//        bitSet.oracle = (java.util.BitSet) oracle.clone();

        return bitSet;
    }

    public void makeones(int size) {
        for (int i = 0; i < size; i++) set(i);
    }

    public int length() {
        if (count == 0)
            return 0;
        else if (count == 1)
            return oneValue + 1;
        else return severalValues.length();
    }

    public boolean intersects(BitSet other) {
        if (count == 0 || other.count == 0)
            return false;
        else if (count == 1) {
            if (other.count == 1)
                return oneValue == other.oneValue;
            else
                return other.severalValues.get(oneValue);
        } else if (other.count == 1)
            return severalValues.get(other.oneValue);
        else
            return severalValues.intersects(other.severalValues);
    }

    public boolean contains(int b) {
        if (count == 0)
            return false;
        if (count == 1)
            return oneValue == b;
        else
            return severalValues.get(b);
    }
}

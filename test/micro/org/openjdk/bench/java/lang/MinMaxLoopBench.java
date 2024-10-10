package org.openjdk.bench.java.lang;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 4, time = 5)
@Fork(2)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
public class MinMaxLoopBench
{
    @State(Scope.Thread)
    public static final class LoopState {
        @Param({"100", "1000", "10000"})
        int size;

        /**
         * Probability of one of the min/max branches being taken.
         * For max, this value represents the percentage of branches in which
         * the value will be bigger or equal than the current max.
         * For min, this value represents the percentage of branches in which
         * the value will be smaller or equal than the current min.
         */
        @Param({"50", "80", "100"})
        int probability;

        int[] minIntA;
        int[] minIntB;
        long[] minLongA;
        long[] minLongB;
        int[] maxIntA;
        int[] maxIntB;
        long[] maxLongA;
        long[] maxLongB;
        int[] resultIntArray;
        long[] resultLongArray;

        @Setup
        public void setup() {
            final long[][] longs = distributeLongRandomIncrement(size, probability);
            maxLongA = longs[0];
            maxLongB = longs[1];
            maxIntA = toInts(maxLongA);
            maxIntB = toInts(maxLongB);
            minLongA = negate(maxLongA);
            minLongB = negate(maxLongB);
            minIntA = toInts(minLongA);
            minIntB = toInts(minLongB);
            resultIntArray = new int[size];
            resultLongArray = new long[size];
        }
        static long[] negate(long[] nums) {
            return LongStream.of(nums).map(l -> -l).toArray();
        }

        static int[] toInts(long[] nums) {
            return Arrays.stream(nums).mapToInt(i -> (int) i).toArray();
        }

        static long[][] distributeLongRandomIncrement(int size, int probability) {
            long[][] result;
            int aboveCount, abovePercent;

            // Iterate until you find a set that matches the requirement probability
            do {
                long max = ThreadLocalRandom.current().nextLong(10);
                result = new long[2][size];
                result[0][0] = max;
                result[1][0] = max - 1;

                aboveCount = 0;
                for (int i = 1; i < result[0].length; i++) {
                    long value;
                    if (ThreadLocalRandom.current().nextLong(101) <= probability) {
                        long increment = ThreadLocalRandom.current().nextLong(10);
                        value = max + increment;
                        aboveCount++;
                    } else {
                        // Decrement by at least 1
                        long decrement = ThreadLocalRandom.current().nextLong(10) + 1;
                        value = max - decrement;
                    }
                    result[0][i] = value;
                    result[1][i] = max;
                    max = Math.max(max, value);
                }

                abovePercent = ((aboveCount + 1) * 100) / size;
            } while (abovePercent != probability);

            return result;
        }
    }

    @Benchmark
    public int[] intLoopMin(LoopState state) {
        for (int i = 0; i < state.size; i++) {
            state.resultIntArray[i] = Math.min(state.minIntA[i], state.minIntB[i]);
        }
        return state.resultIntArray;
    }

    @Benchmark
    public int[] intLoopMax(LoopState state) {
        for (int i = 0; i < state.size; i++) {
            state.resultIntArray[i] = Math.max(state.maxIntA[i], state.maxIntB[i]);
        }
        return state.resultIntArray;
    }

    @Benchmark
    public int intReductionMin(LoopState state) {
        int result = 0;
        for (int i = 0; i < state.size; i++) {
            final int v = 11 * state.minIntA[i];
            result = Math.min(result, v);
        }
        return result;
    }

    @Benchmark
    public int intReductionMax(LoopState state) {
        int result = 0;
        for (int i = 0; i < state.size; i++) {
            final int v = 11 * state.maxIntA[i];
            result = Math.max(result, v);
        }
        return result;
    }

    @Benchmark
    public long[] longLoopMin(LoopState state) {
        for (int i = 0; i < state.size; i++) {
            state.resultLongArray[i] = Math.min(state.minLongA[i], state.minLongB[i]);
        }
        return state.resultLongArray;
    }

    @Benchmark
    public long[] longLoopMax(LoopState state) {
        for (int i = 0; i < state.size; i++) {
            state.resultLongArray[i] = Math.max(state.maxLongA[i], state.maxLongB[i]);
        }
        return state.resultLongArray;
    }

    @Benchmark
    public long longReductionMin(LoopState state) {
        long result = 0;
        for (int i = 0; i < state.size; i++) {
            final long v = 11 * state.minLongA[i];
            result = Math.min(result, v);
        }
        return result;
    }

    @Benchmark
    public long longReductionMax(LoopState state) {
        long result = 0;
        for (int i = 0; i < state.size; i++) {
            final long v = 11 * state.maxLongA[i];
            result = Math.max(result, v);
        }
        return result;
    }
}

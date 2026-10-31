package net.grug.minecraft.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order the test runner runs its tests in: the tests that failed the last recorded run first,
 * then the rest, both in descending order of the time the record holds for them, and ties by name.
 *
 * <p>The suite is serial, so a green run takes as long as it does whatever the order is; what the
 * order changes is where a failure lands. A test that failed last is the first thing the next run
 * runs, so it fails in the first minute of that run instead of the last minute of the one before,
 * and the record that says so is what the runner writes back when a run ends and the next one
 * reads when it starts.
 */
public final class GrugTestOrder {
    private GrugTestOrder() {}

    /** What one run records about its tests: how long each took, and the one that failed, if any. */
    public static final class Record {
        /** The tests that failed the run the record came from, so the next one starts there. */
        public final Set<String> failed;

        /** How long each test took, in nanoseconds, keyed by the test's path. */
        public final Map<String, Long> times;

        public Record(Set<String> failed, Map<String, Long> times) {
            this.failed = failed;
            this.times = times;
        }
    }

    /**
     * Every test in {@code currentTests}, the ones {@code previouslyFailed} names first and the
     * rest after, each group in descending order of its time in {@code recordedTimes}, a test with
     * no recorded time last of its group and ties broken by name. A test the record names but
     * {@code currentTests} does not is one the loader no longer has, so it drops out.
     */
    public static List<String> orderTests(
            Map<String, Long> recordedTimes,
            Set<String> previouslyFailed,
            Collection<String> currentTests) {
        List<String> all = new ArrayList<>(currentTests);
        all.sort(String::compareTo);
        List<String> failedFirst = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String test : all) {
            if (previouslyFailed.contains(test)) {
                failedFirst.add(test);
            } else {
                rest.add(test);
            }
        }
        Comparator<String> slowestFirst =
                (a, b) -> {
                    int byTime =
                            Long.compare(
                                    recordedTimes.getOrDefault(b, 0L),
                                    recordedTimes.getOrDefault(a, 0L));
                    if (byTime != 0) {
                        return byTime;
                    }
                    return a.compareTo(b);
                };
        failedFirst.sort(slowestFirst);
        rest.sort(slowestFirst);
        List<String> ordered = new ArrayList<>(all.size());
        ordered.addAll(failedFirst);
        ordered.addAll(rest);
        return ordered;
    }

    /**
     * Reads the record a run writes back: an object with a "failed" list of test paths and a
     * "times" object mapping each test's path to how long it took, in nanoseconds.
     *
     * @throws IllegalArgumentException when the text is not the record, so the runner can say so
     *     and fall back to name order instead of ordering the suite by a broken record.
     */
    public static Record parseRecord(String text) {
        JsonElement element;
        try {
            element = JsonParser.parseString(text);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("the test order record is not JSON", e);
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(
                    "the test order record must be a JSON object with a 'failed' list and a"
                            + " 'times' object");
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement failed = object.get("failed");
        if (failed == null || !failed.isJsonArray()) {
            throw new IllegalArgumentException(
                    "the test order record needs a 'failed' list of the tests that failed");
        }
        JsonElement times = object.get("times");
        if (times == null || !times.isJsonObject()) {
            throw new IllegalArgumentException(
                    "the test order record needs a 'times' object mapping each test's path to its"
                            + " nanoseconds");
        }
        Set<String> failedTests = new LinkedHashSet<>();
        for (JsonElement item : failed.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(
                        "the test order record's 'failed' list holds test paths, each a string");
            }
            failedTests.add(item.getAsString());
        }
        Map<String, Long> recordedTimes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : times.getAsJsonObject().entrySet()) {
            Long nanos = nanoseconds(entry.getValue());
            if (nanos < 0) {
                throw new IllegalArgumentException(
                        "the test order record holds negative nanoseconds for " + entry.getKey());
            }
            recordedTimes.put(entry.getKey(), nanos);
        }
        return new Record(failedTests, recordedTimes);
    }

    /**
     * The time one "times" entry holds, in nanoseconds.
     *
     * <p>The number is checked against the text it was written as, because the parser keeps that
     * text and would otherwise read {@code 1.5} as {@code 1} and say nothing.
     *
     * @throws IllegalArgumentException when the entry is not a whole non-negative number.
     */
    private static Long nanoseconds(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                    "the test order record's 'times' hold whole nanoseconds, not " + value);
        }
        String text = value.getAsString();
        if (!text.matches("-?(0|[1-9][0-9]{0,17})")) {
            throw new IllegalArgumentException(
                    "the test order record's 'times' hold whole nanoseconds, not " + value);
        }
        return Long.parseLong(text);
    }
}

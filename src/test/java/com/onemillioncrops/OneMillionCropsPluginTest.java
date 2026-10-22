package com.onemillioncrops;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneMillionCropsPluginTest {
    @Test
    void operationEndsAndReportsFailureWhenCompletionThrows() {
        List<String> events = new ArrayList<>();

        boolean succeeded = OneMillionCropsPlugin.runThenEnd(() -> {
            throw new IllegalArgumentException("Unknown celebration sound: nope");
        }, () -> events.add("ended"), exception -> events.add("logged " + exception.getMessage()));

        assertFalse(succeeded);
        assertEquals(List.of("logged Unknown celebration sound: nope", "ended"), events);
    }

    @Test
    void operationEndsAfterSuccessfulCompletion() {
        List<String> events = new ArrayList<>();

        boolean succeeded = OneMillionCropsPlugin.runThenEnd(() -> events.add("applied"),
                () -> events.add("ended"), exception -> events.add("logged"));

        assertTrue(succeeded);
        assertEquals(List.of("applied", "ended"), events);
    }
}

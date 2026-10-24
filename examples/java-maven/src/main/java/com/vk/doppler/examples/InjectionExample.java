package com.vk.doppler.examples;

import java.util.Map;

public final class InjectionExample {
    public static void main(String[] arguments) {
        String mode = arguments.length == 0 ? "plain" : arguments[0];
        if (arguments.length > 1 || !(mode.equals("plain") || mode.equals("custom"))) {
            System.err.println("FAIL: use plain or custom mode.");
            System.exit(2);
        }

        boolean custom = mode.equals("custom");
        Map<String, String> expected = custom
            ? Map.of("APP_MESSAGE", "doppler-example", "APP_NUMBER", "42")
            : Map.of("EXAMPLE_MESSAGE", "doppler-example", "EXAMPLE_NUMBER", "42");
        boolean valuesMatch = expected.entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(System.getenv(entry.getKey())));
        boolean originalNamesAbsent = !custom ||
            (System.getenv("EXAMPLE_MESSAGE") == null && System.getenv("EXAMPLE_NUMBER") == null);
        if (!valuesMatch || !originalNamesAbsent) {
            System.err.println("FAIL: example variables are missing, unexpected, or still present under their original names.");
            System.exit(1);
        }
        System.out.println("PASS: both Doppler example variables reached Java.");
    }
}
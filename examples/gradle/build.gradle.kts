val examples = mapOf(
    "verifyInjection" to mapOf("EXAMPLE_MESSAGE" to "doppler-example", "EXAMPLE_NUMBER" to "42"),
    "verifyCustomInjection" to mapOf("APP_MESSAGE" to "doppler-example", "APP_NUMBER" to "42"),
)

examples.forEach { (taskName, expected) ->
    tasks.register(taskName) {
        group = "verification"
        description = "Verify Doppler example variables without printing their values."
        doLast {
            val valuesMatch = expected.all { (name, value) -> System.getenv(name) == value }
            val originalNamesAbsent = taskName != "verifyCustomInjection" ||
                listOf("EXAMPLE_MESSAGE", "EXAMPLE_NUMBER").none { System.getenv(it) != null }
            if (!valuesMatch || !originalNamesAbsent) {
                throw GradleException("Example variables are missing, unexpected, or still present under their original names. Check the Doppler defaults, injection setting, and name mappings.")
            }
            logger.lifecycle("PASS: $taskName received both expected variables.")
        }
    }
}
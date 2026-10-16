package com.vk.doppler.cli

/** Allowlisted account metadata for the connection dialog; never contains a token preview. */
data class DopplerAccountInfo(
    val authContextId: String,
    val slug: String,
    val type: String,
    val workplaceSlug: String,
    val workplaceName: String?,
    val name: String? = null,
)

/** A Doppler project as returned by `doppler projects --json`. */
data class DopplerProject(
    val slug: String,
    val name: String,
    val description: String?,
) {
    val displayName: String get() = name.ifBlank { slug }
}

/** A config (e.g. `dev`) belonging to an environment (e.g. `dev`) inside a project. */
data class DopplerConfig(
    val name: String,
    val environment: String,
)

/** A single secret. [raw] is `null` when the secret is restricted and cannot be read. */
data class DopplerSecret(
    val name: String,
    val raw: String?,
    val computed: String?,
    val note: String?,
) {
    val restricted: Boolean get() = raw == null
}

object DopplerReservedSecrets {
    /** Injected by Doppler on every config and rejected by the API on write. */
    val NAMES: Set<String> = setOf("DOPPLER_PROJECT", "DOPPLER_CONFIG", "DOPPLER_ENVIRONMENT")

    fun isReserved(name: String): Boolean = name in NAMES
}

object DopplerSecretNames {
    /** Doppler secret names may contain only ASCII letters, digits, `_`, `/`, `:`, `.`, and `-`. */
    private val VALID = Regex("[A-Za-z0-9_/:.-]+")

    fun isValid(name: String): Boolean = VALID.matches(name)
}

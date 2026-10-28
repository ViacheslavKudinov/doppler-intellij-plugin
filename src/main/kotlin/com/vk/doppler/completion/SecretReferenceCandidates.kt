package com.vk.doppler.completion

/** Secret names by project slug, then config name. */
internal typealias SecretNames = Map<String, Map<String, Set<String>>>

internal data class SecretReferenceLocation(
    val projectSlug: String,
    val configName: String,
)

internal enum class SecretReferenceScope(
    val priority: Double,
) {
    CONFIG(3.0),
    PROJECT(2.0),
    ACCOUNT(1.0),
}

internal data class SecretReferenceCandidate(
    val reference: String,
    val scope: SecretReferenceScope,
    val origin: String,
)

/**
 * Builds the references that can be written from [SecretReferenceLocation]:
 * `${secret}`, `${config.secret}` and `${project.config.secret}`.
 */
internal object SecretReferenceCandidates {
    fun find(
        current: SecretReferenceLocation,
        currentNames: Collection<String>,
        index: SecretNames,
        prefix: String,
    ): List<SecretReferenceCandidate> {
        val result = ArrayList<SecretReferenceCandidate>()
        val currentOrigin = origin(current.projectSlug, current.configName)

        for (name in currentNames) {
            if (name.startsWith(prefix, ignoreCase = true)) {
                result += SecretReferenceCandidate(name, SecretReferenceScope.CONFIG, currentOrigin)
            }
        }

        val projectConfigs = index[current.projectSlug].orEmpty()
        for ((config, names) in projectConfigs) {
            if (config == current.configName) continue
            addQualified("$config.", names, SecretReferenceScope.PROJECT, origin(current.projectSlug, config), prefix, result)
        }

        // The fully qualified form of the current project is only offered once it has been typed.
        val projectLead = "${current.projectSlug}."
        if (prefix.startsWith(projectLead, ignoreCase = true)) {
            val configs = projectConfigs + (current.configName to currentNames.toSet())
            for ((config, names) in configs) {
                addQualified(
                    "$projectLead$config.",
                    names,
                    SecretReferenceScope.PROJECT,
                    origin(current.projectSlug, config),
                    prefix,
                    result,
                )
            }
        }

        for ((project, configs) in index) {
            if (project == current.projectSlug || !matchesLead("$project.", prefix)) continue
            for ((config, names) in configs) {
                addQualified(
                    "$project.$config.",
                    names,
                    SecretReferenceScope.ACCOUNT,
                    origin(project, config),
                    prefix,
                    result,
                )
            }
        }
        return result
    }

    private fun addQualified(
        qualifier: String,
        names: Set<String>,
        scope: SecretReferenceScope,
        origin: String,
        prefix: String,
        out: MutableList<SecretReferenceCandidate>,
    ) {
        if (!matchesLead(qualifier, prefix)) return
        if (qualifier.startsWith(prefix, ignoreCase = true)) {
            names.mapTo(out) { SecretReferenceCandidate(qualifier + it, scope, origin) }
        } else {
            val remainder = prefix.substring(qualifier.length)
            for (name in names) {
                if (name.startsWith(remainder, ignoreCase = true)) {
                    out += SecretReferenceCandidate(qualifier + name, scope, origin)
                }
            }
        }
    }

    /** Whether references starting with [lead] can still match what has been typed. */
    private fun matchesLead(
        lead: String,
        prefix: String,
    ): Boolean = lead.startsWith(prefix, ignoreCase = true) || prefix.startsWith(lead, ignoreCase = true)

    private fun origin(
        project: String,
        config: String,
    ) = "$project/$config"
}

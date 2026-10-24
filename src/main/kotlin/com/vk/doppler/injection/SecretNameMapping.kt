package com.vk.doppler.injection

import com.vk.doppler.cli.DopplerReservedSecrets
import com.vk.doppler.cli.DopplerSecretNames

/** One custom rename: the secret [name] in Doppler is injected as [newName]. */
data class SecretNameMapping(var name: String = "", var newName: String = "")

private fun isValidEnvironmentName(name: String): Boolean =
    DopplerSecretNames.isValid(name) && !DopplerReservedSecrets.isReserved(name)

/** Returns a user-facing problem with [mappings], or null when they can be applied. Fully blank rows are ignored. */
internal fun validateSecretNameMappings(mappings: List<SecretNameMapping>): String? {
    val used = mappings.filter { it.name.isNotBlank() || it.newName.isNotBlank() }
    if (used.any { it.name.isBlank() }) return "Every mapping needs a current secret name."
    if (used.any { it.newName.isBlank() }) return "Every mapping needs a new secret name."
    if (used.any { !DopplerSecretNames.isValid(it.newName) }) {
        return "Invalid new secret name. Use only letters, digits, _, /, :, ., and -."
    }
    if (used.any { DopplerReservedSecrets.isReserved(it.newName) }) {
        return "New secret names cannot use names managed by Doppler."
    }
    used.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        ?.let { return "\"${it.key}\" is mapped more than once." }
    used.groupBy { it.newName }.entries.firstOrNull { it.value.size > 1 }
        ?.let { return "More than one secret is mapped to \"${it.key}\"." }
    return null
}

/**
 * Renames secrets listed in [mappings]; all other secrets keep their names. Renames use the original
 * names, so A->B and B->C do not chain. A mapped value wins over an unmapped secret with the same new name.
 */
internal fun applySecretNameMapping(secrets: Map<String, String>, mappings: List<SecretNameMapping>): Map<String, String> {
    val renames = LinkedHashMap<String, String>()
    for (mapping in mappings) {
        if (mapping.name.isNotEmpty() && isValidEnvironmentName(mapping.newName) && mapping.name !in renames) {
            renames[mapping.name] = mapping.newName
        }
    }
    if (renames.isEmpty()) return secrets
    val result = LinkedHashMap<String, String>()
    for ((name, value) in secrets) {
        if (name !in renames) result[name] = value
    }
    for ((name, value) in secrets) {
        renames[name]?.let { result[it] = value }
    }
    return result
}

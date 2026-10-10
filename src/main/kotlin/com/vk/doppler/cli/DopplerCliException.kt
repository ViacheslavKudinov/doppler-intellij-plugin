package com.vk.doppler.cli

/**
 * Raised for every failure mode of the `doppler` CLI: missing binary, non-zero exit,
 * timeout, or an `{"error": "..."}` payload.
 */
class DopplerCliException(
    message: String,
    val kind: Kind = Kind.GENERIC,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind { CLI_NOT_FOUND, NOT_AUTHENTICATED, GENERIC }

    val isRecoverableBySetup: Boolean
        get() = kind == Kind.CLI_NOT_FOUND || kind == Kind.NOT_AUTHENTICATED
}

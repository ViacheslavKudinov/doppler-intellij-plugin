package com.vk.doppler.cli

/** Doppler's documented injection name transformers; null means no CLI transformation. */
enum class DopplerNameTransformer(val cliName: String?, private val label: String) {
    NONE(null, "none"),
    CAMEL("camel", "camel - API_KEY → apiKey"),
    UPPER_CAMEL("upper-camel", "upper-camel - API_KEY → ApiKey"),
    LOWER_SNAKE("lower-snake", "lower-snake - API_KEY → api_key"),
    TF_VAR("tf-var", "tf-var - API_KEY → TF_VAR_api_key"),
    DOTNET_ENV("dotnet-env", "dotnet-env - SMTP__USER_NAME → Smtp__UserName"),
    LOWER_KEBAB("lower-kebab", "lower-kebab - API_KEY → api-key"),

    /** Not a CLI transformer: the plugin renames the chosen secrets after download. */
    CUSTOM(null, "custom - rename selected secrets");

    override fun toString(): String = label
}

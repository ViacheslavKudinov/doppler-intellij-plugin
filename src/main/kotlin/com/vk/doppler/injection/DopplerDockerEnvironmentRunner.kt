package com.vk.doppler.injection

import com.intellij.docker.DockerDeploymentConfiguration
import com.intellij.docker.agent.settings.DockerEnvVarImpl
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.ExecutionException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element

/** Docker's existing runner retains responsibility for deployment and debugger attachment. */
class DopplerDockerEnvironmentRunner : DopplerEnvironmentRunner() {
    override fun getRunnerId(): String = "DopplerDockerEnvironment"
    override fun accepts(profile: RunProfile): Boolean = profile is RunConfiguration && profile.type.id == "docker-deploy"

    override fun environment(profile: RunProfile): Map<String, String> = safely {
        readSettings(serialized(profile as RunConfiguration)).envVars.associate { it.name to it.value }
    }

    override fun copyProfile(profile: RunProfile, values: Map<String, String>): RunConfiguration = safely {
        if (values.any { (name, value) -> org.jdom.Verifier.checkCharacterData(name) != null || org.jdom.Verifier.checkCharacterData(value) != null }) {
            throw ExecutionException("Docker launch settings cannot represent an environment value.")
        }
        val copy = (profile as RunConfiguration).clone()
        val external = serialized(copy)
        val deployment = external.getChild("deployment")
            ?: throw ExecutionException("Select a Docker image, Dockerfile, or Compose source before launching.")
        val settings = copyDockerEnvironment(readSettings(external), values)
        val settingsElement = Element("settings")
        XmlSerializer.serializeInto(settings, settingsElement)
        deployment.removeChild("settings")
        deployment.addContent(settingsElement)
        try {
            // Public RunConfiguration serialization round-trips only this unregistered copy in memory.
            copy.readExternal(external)
        } catch (_: com.intellij.openapi.util.InvalidDataException) {
            throw ExecutionException("Could not prepare the Docker launch environment.")
        }
        copy
    }

    private inline fun <T> safely(action: () -> T): T = try {
        action()
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (_: Exception) {
        // XML validation exceptions may include values; never propagate them or their causes to IDE logs.
        throw ExecutionException("Could not prepare the Docker launch environment. Check the Docker run configuration.")
    }

    private fun serialized(profile: RunConfiguration): Element = Element("configuration").also {
        try { profile.writeExternal(it) } catch (_: com.intellij.openapi.util.WriteExternalException) {
            throw ExecutionException("Could not read the Docker launch settings.")
        }
    }

    private fun readSettings(external: Element): DockerDeploymentConfiguration =
        external.getChild("deployment")?.getChild("settings")?.let {
            XmlSerializer.deserialize(it, DockerDeploymentConfiguration::class.java)
        } ?: DockerDeploymentConfiguration()
}

/** Copy before adding secrets; never store the injected profile in RunManager. */
internal fun copyDockerEnvironment(
    original: DockerDeploymentConfiguration,
    environment: Map<String, String>,
): DockerDeploymentConfiguration = DockerDeploymentConfiguration().apply {
    loadState(original)
    envVars = environment.mapTo(mutableListOf()) { (name, value) -> DockerEnvVarImpl(name, value) }
    showCommandPreview = false
}

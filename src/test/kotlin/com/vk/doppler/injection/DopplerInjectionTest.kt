package com.vk.doppler.injection

import com.intellij.docker.DockerDeploymentConfiguration
import com.intellij.docker.agent.settings.DockerEnvVarImpl
import com.intellij.docker.deploymentSource.DockerComposeDeploymentSourceType
import com.intellij.docker.deploymentSource.DockerFileDeploymentSourceType
import com.intellij.docker.deploymentSource.DockerImageDeploymentSourceType
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.NodeTargetRunOptions
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.Disposer
import com.intellij.remoteServer.configuration.ServerConfiguration
import com.intellij.remoteServer.impl.configuration.deployment.DeployToServerRunConfiguration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.ExtensionTestUtil
import com.jetbrains.nodejs.run.NodeJsRunConfigurationType
import com.jetbrains.python.run.PythonConfigurationType
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.vk.doppler.settings.DopplerInjectionSettings
import com.vk.doppler.settings.DopplerSettings
import com.vk.doppler.cli.DopplerNameTransformer
import org.gradle.tooling.LongRunningOperation
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContext
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.Assume.assumeFalse
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DopplerInjectionTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    private lateinit var previousCli: String
    private lateinit var previousSettings: DopplerInjectionSettings.State
    private val service get() = DopplerInjectionService.getInstance(project)

    override fun setUp() {
        super.setUp()
        assumeFalse(SystemInfo.isWindows)
        directory = Files.createTempDirectory("doppler-injection-test-")
        previousCli = DopplerSettings.getInstance().cliPath
        previousSettings = DopplerInjectionSettings.getInstance(project).state.copy()
        TrustedProjects.setProjectTrusted(project, true)
        Files.writeString(directory.resolve("account"), "one")
        Files.writeString(directory.resolve("config"), "dev")
        val script = directory.resolve("doppler")
        Files.writeString(script, """
            #!/bin/sh
            case "${'$'}1" in
              configure) printf '{"project":"example","config":"%s"}' "${'$'}(cat '$directory/config')" ;;
              me)
                printf '%s\n' "${'$'}*" >> '$directory/accounts'
                if [ -f '$directory/logged-out' ]; then printf '{"error":"unauthorized"}'; exit 1; fi
                printf '{"slug":"%s","type":"personal","token_preview":"synthetic","workplace":{"slug":"test"}}' "${'$'}(cat '$directory/account')" ;;
              secrets)
                printf '%s\n' "${'$'}*" >> '$directory/downloads'
                no_fallback=false
                for arg in "${'$'}@"; do
                    if [ "${'$'}arg" = --no-fallback ]; then no_fallback=true; fi
                done
                if [ -f '$directory/fail' ]; then
                    if [ "${'$'}no_fallback" = false ] && [ -f '$directory/fallback' ]; then cat '$directory/fallback'; exit 0; fi
                    printf '{"error":"synthetic-sensitive-error"}'; exit 1
                fi
                if [ "${'$'}no_fallback" = false ]; then printf '{"error":"Missing required no-fallback flag"}'; exit 1; fi
                if [ -f '$directory/invalid' ]; then printf '{"BAD":"\u0000"}'; exit 0; fi
                if [ -f '$directory/xml-control' ]; then printf '%s' '{"KEY":"synthetic\u0001sensitive"}'; exit 0; fi
                if [ -f '$directory/remote' ]; then cat '$directory/remote'; exit 0; fi
                if [ -f '$directory/switch-account' ]; then printf 'two' > '$directory/account'; fi
                printf '{"KEY":"resolved-%s-%s","EMPTY":"","MULTILINE":"one\ntwo","DOPPLER_CONFIG":"%s"}' "${'$'}(cat '$directory/account')" "${'$'}(cat '$directory/config')" "${'$'}(cat '$directory/config')" ;;
              *) exit 1 ;;
            esac
        """.trimIndent())
        assertTrue(script.toFile().setExecutable(true))
        DopplerSettings.getInstance().cliPath = script.toString()
        DopplerInjectionSettings.getInstance(project).enabled = true
    }

    override fun tearDown() {
        try {
            if (::directory.isInitialized) {
                DopplerSettings.getInstance().cliPath = previousCli
                DopplerInjectionSettings.getInstance(project).loadState(previousSettings)
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        } finally { super.tearDown() }
    }

    fun `test every launch downloads default config resolved values and preserves local overrides`() {
        val merged = background { service.mergeEnvironment(mapOf("KEY" to "local")) }
        assertEquals("local", merged["KEY"])
        assertEquals("", merged["EMPTY"])
        assertEquals("one\ntwo", merged["MULTILINE"])
        assertEquals("dev", merged["DOPPLER_CONFIG"])
        assertEquals("resolved-one-dev", background { service.mergeEnvironment(emptyMap()) }["KEY"])
        val downloads = Files.readAllLines(directory.resolve("downloads"))
        assertEquals(2, downloads.size)
        assertTrue(downloads.all { it == "secrets download --no-file --no-fallback --format json --project example --config dev --scope ${project.basePath} --json --no-check-version" })
        assertTrue(downloads.none { it.contains("resolved") })
        assertTrue(Files.readAllLines(directory.resolve("accounts")).all { it.contains("--scope ${project.basePath}") })
    }

    fun `test changed added and deleted remote secrets are reflected on every launch`() {
        assertEquals("resolved-one-dev", background { service.mergeEnvironment(emptyMap()) }["KEY"])
        Files.writeString(directory.resolve("remote"), """{"KEY":"updated","ADDED":"new"}""")
        val changed = background { service.mergeEnvironment(emptyMap()) }
        assertEquals("updated", changed["KEY"])
        assertEquals("new", changed["ADDED"])
        Files.writeString(directory.resolve("remote"), """{"ADDED":"new"}""")
        val deleted = background { service.mergeEnvironment(emptyMap()) }
        assertFalse(deleted.containsKey("KEY"))
        assertEquals("new", deleted["ADDED"])
    }

    fun `test every name transformer is forwarded to CLI and transformed output preserves local overrides`() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val examples = mapOf(
            DopplerNameTransformer.CAMEL to "apiKey",
            DopplerNameTransformer.UPPER_CAMEL to "ApiKey",
            DopplerNameTransformer.LOWER_SNAKE to "api_key",
            DopplerNameTransformer.TF_VAR to "TF_VAR_api_key",
            DopplerNameTransformer.DOTNET_ENV to "ApiKey",
            DopplerNameTransformer.LOWER_KEBAB to "api-key",
        )
        for ((transformer, name) in examples) {
            settings.nameTransformer = transformer
            // The fake CLI returns transformed names; production delegates transformation to Doppler.
            Files.writeString(directory.resolve("remote"), """{"$name":"synthetic-transformed","OTHER":"remote"}""")
            assertEquals("synthetic-transformed", background { service.mergeEnvironment(emptyMap()) }[name])
            val existing = mapOf(name to "local")
            val merged = background { service.mergeEnvironment(existing) }
            assertEquals("local", merged[name])
            assertEquals("remote", merged["OTHER"])
            assertFalse(merged.containsKey("API_KEY"))
            assertEquals(mapOf(name to "local"), existing)
            assertTrue(Files.readAllLines(directory.resolve("downloads")).last()
                .contains("--name-transformer ${transformer.cliName} --scope"))
        }
        settings.nameTransformer = DopplerNameTransformer.NONE
        Files.writeString(directory.resolve("remote"), """{"API_KEY":"unchanged"}""")
        assertEquals(mapOf("API_KEY" to "unchanged"), background { service.mergeEnvironment(emptyMap()) })
        assertFalse(Files.readAllLines(directory.resolve("downloads")).last().contains("--name-transformer"))
    }

    fun `test transformer CLI failure aborts injection without exposing error output`() {
        DopplerInjectionSettings.getInstance(project).nameTransformer = DopplerNameTransformer.CAMEL
        Files.createFile(directory.resolve("fail"))
        try {
            background { service.mergeEnvironment(emptyMap()) }
            fail("A transformer failure must abort injection")
        } catch (error: ExecutionException) {
            assertFalse(error.message.orEmpty().contains("synthetic-sensitive-error"))
            assertNull(error.cause)
        }
        assertTrue(Files.readAllLines(directory.resolve("downloads")).last().contains("--name-transformer camel"))
    }

    fun `test account and config changes and failures do not reuse stale secrets`() {
        assertEquals("resolved-one-dev", background { service.mergeEnvironment(emptyMap()) }["KEY"])
        Files.writeString(directory.resolve("account"), "two")
        assertEquals("resolved-two-dev", background { service.mergeEnvironment(emptyMap()) }["KEY"])
        Files.writeString(directory.resolve("config"), "stg")
        assertEquals("resolved-two-stg", background { service.mergeEnvironment(emptyMap()) }["KEY"])
        Files.createFile(directory.resolve("fail"))
        try {
            background { service.mergeEnvironment(emptyMap()) }
            fail("Failure must abort injection")
        } catch (error: ExecutionException) {
            assertFalse(error.message.orEmpty().contains("synthetic-sensitive-error"))
            assertNull(error.cause)
        }
        Files.delete(directory.resolve("fail"))
        assertEquals("resolved-two-stg", background { service.mergeEnvironment(emptyMap()) }["KEY"])
    }

    fun `test failed download aborts despite previously available fallback secrets`() {
        assertEquals("resolved-one-dev", background { service.mergeEnvironment(emptyMap()) }["KEY"])
        // Model a pre-existing encrypted CLI fallback using synthetic fixture data only.
        Files.writeString(directory.resolve("fallback"), """{"KEY":"synthetic-stale-fallback"}""")
        Files.createFile(directory.resolve("fail"))
        try {
            background { service.mergeEnvironment(emptyMap()) }
            fail("Unavailable remote secrets must not fall back to stored values")
        } catch (error: ExecutionException) {
            assertNull(error.cause)
            assertFalse(error.message.orEmpty().contains("synthetic-stale-fallback"))
            assertFalse(error.message.orEmpty().contains("synthetic-sensitive-error"))
        }
        assertTrue(Files.readAllLines(directory.resolve("downloads")).all { "--no-fallback" in it })
    }

    fun `test disabled injection skips CLI and enabling it fetches fresh values`() {
        background { service.mergeEnvironment(emptyMap()) }
        val downloadsBefore = Files.readAllLines(directory.resolve("downloads")).size
        DopplerInjectionSettings.getInstance(project).enabled = false
        assertEquals(mapOf("LOCAL" to "one"), service.mergeEnvironment(mapOf("LOCAL" to "one")))
        assertEquals(downloadsBefore, Files.readAllLines(directory.resolve("downloads")).size)
        Files.writeString(directory.resolve("account"), "two")
        DopplerInjectionSettings.getInstance(project).enabled = true
        assertEquals("resolved-two-dev", background { service.mergeEnvironment(emptyMap()) }["KEY"])
    }

    fun `test account switch while fetching logout and invalid values abort safely`() {
        Files.createFile(directory.resolve("switch-account"))
        assertSafeFailure()
        Files.delete(directory.resolve("switch-account"))
        assertEquals("resolved-two-dev", background { service.mergeEnvironment(emptyMap()) }["KEY"])
        Files.createFile(directory.resolve("logged-out"))
        assertSafeFailure()
        Files.delete(directory.resolve("logged-out"))
        Files.createFile(directory.resolve("invalid"))
        assertSafeFailure()
        Files.delete(directory.resolve("invalid"))
        assertEquals("resolved-two-dev", background { service.mergeEnvironment(emptyMap()) }["KEY"])
    }

    fun `test missing folder defaults skip injection without downloading secrets`() {
        Files.writeString(directory.resolve("config"), "")
        val existing = mapOf("LOCAL" to "value")
        assertSame(existing, background { service.mergeEnvironment(existing) })
        assertFalse(Files.exists(directory.resolve("downloads")))
    }

    fun `test Java and Maven patch launch parameters without changing stored profiles`() {
        val java = ApplicationConfigurationType.getInstance().configurationFactories.first().createTemplateConfiguration(project)
        val maven = MavenRunConfigurationType.getInstance().configurationFactories.first().createTemplateConfiguration(project)
        val extension = DopplerJavaEnvironmentExtension()
        for (profile in listOf(java, maven)) {
            val configuration = profile as RunConfigurationBase<*>
            val before = org.jdom.Element("configuration")
            configuration.writeExternal(before)
            val parameters = JavaParameters().apply { env = mapOf("KEY" to "local") }
            assertTrue(extension.isApplicableFor(configuration))
            background { extension.updateJavaParameters(configuration, parameters, null) }
            assertEquals("local", parameters.env["KEY"])
            assertEquals("dev", parameters.env["DOPPLER_CONFIG"])
            val after = org.jdom.Element("configuration")
            configuration.writeExternal(after)
            assertEquals(com.intellij.openapi.util.JDOMUtil.writeElement(before), com.intellij.openapi.util.JDOMUtil.writeElement(after))
        }
    }

    fun `test Python delegates Run and Debug using an isolated profile with local overrides`() {
        val configuration = PythonConfigurationType.getInstance().factory.createTemplateConfiguration(project) as AbstractPythonRunConfiguration<*>
        configuration.envs = mapOf("KEY" to "local")
        val originalEnvironment = configuration.envs.toMap()
        val runner = DopplerPythonEnvironmentRunner()
        assertEquals("DopplerPythonEnvironment", runner.runnerId)
        for (executor in listOf(DefaultRunExecutor.getRunExecutorInstance(), DefaultDebugExecutor.getDebugExecutorInstance())) {
            val launch = runThroughRunner(runner, configuration, executor)
            val copy = launch.runProfile as AbstractPythonRunConfiguration<*>
            assertNotSame(configuration, copy)
            assertEquals("local", copy.envs["KEY"])
            assertEquals("dev", copy.envs["DOPPLER_CONFIG"])
        }
        assertEquals(originalEnvironment, configuration.envs)
        assertFalse(runner.canRun("Coverage", configuration))
        val java = ApplicationConfigurationType.getInstance().configurationFactories.first().createTemplateConfiguration(project)
        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, java))
        assertFalse(DopplerDockerEnvironmentRunner().canRun(DefaultRunExecutor.EXECUTOR_ID, java))
        DopplerInjectionSettings.getInstance(project).enabled = false
        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, configuration))
    }

    fun `test Node launch session patches transient environment only`() {
        val configuration = NodeJsRunConfigurationType.getInstance().createTemplateConfiguration(project)
        val saved = org.jdom.Element("configuration").also(configuration::writeExternal)
        val extension = DopplerNodeEnvironmentExtension()
        assertTrue(extension.isApplicableFor(configuration))
        val target = NodeTargetRun(NodeJsLocalInterpreter("/synthetic/node"), project, null, NodeTargetRunOptions.of(false))
        target.envData = target.envData.with(mapOf("KEY" to "local"))
        val session = extension.createLaunchSession(configuration, environment())
        background { session.addNodeOptionsTo(target) }
        assertEquals("local", target.envData.envs["KEY"])
        assertEquals("dev", target.envData.envs["DOPPLER_CONFIG"])
        val after = org.jdom.Element("configuration").also(configuration::writeExternal)
        assertEquals(com.intellij.openapi.util.JDOMUtil.writeElement(saved), com.intellij.openapi.util.JDOMUtil.writeElement(after))
    }

    fun `test Gradle Tooling API patches execution operation and skips project sync`() {
        val settings = GradleExecutionSettings().apply { withEnvironmentVariables(mapOf("KEY" to "local")) }
        var injected: Map<String, String>? = null
        val operation = proxy<LongRunningOperation> { method, args ->
            if (method == "setEnvironmentVariables") {
                @Suppress("UNCHECKED_CAST")
                injected = args!![0] as Map<String, String>
            }
            null
        }
        fun context(type: ExternalSystemTaskType) = proxy<GradleExecutionContext> { method, _ -> when (method) {
            "getTaskId" -> ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, type, project)
            "getSettings" -> settings
            "getProject" -> project
            else -> null
        } }
        val extension = DopplerGradleEnvironmentExtension()
        background { extension.configureOperation(operation, context(ExternalSystemTaskType.RESOLVE_PROJECT)) }
        assertNull(injected)
        background { extension.configureOperation(operation, context(ExternalSystemTaskType.EXECUTE_TASK)) }
        assertEquals("local", injected!!["KEY"])
        assertEquals("dev", injected["DOPPLER_CONFIG"])
        assertEquals(mapOf("KEY" to "local"), settings.env)
    }

    fun `test Gradle cancels operation on injection failure even if host swallows extension errors`() {
        var cancelled = false
        val operation = proxy<LongRunningOperation> { method, args ->
            if (method == "withCancellationToken") cancelled = (args!![0] as org.gradle.tooling.CancellationToken).isCancellationRequested
            null
        }
        val context = proxy<GradleExecutionContext> { method, _ -> when (method) {
            "getTaskId" -> ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project)
            "getSettings" -> GradleExecutionSettings()
            "getProject" -> project
            else -> null
        } }
        Files.createFile(directory.resolve("fail"))
        try {
            background { DopplerGradleEnvironmentExtension().configureOperation(operation, context) }
            fail("Expected the launch to be cancelled")
        } catch (_: ExecutionException) {
            assertTrue(cancelled)
        }
    }

    fun `test Docker image and compose settings stay unchanged and preview cannot reveal injected secrets`() {
        val type = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.first { it.id == "docker-deploy" }
        val runner = DopplerDockerEnvironmentRunner()
        assertEquals("DopplerDockerEnvironment", runner.runnerId)
        val sources = listOf(DockerImageDeploymentSourceType.getInstance(), DockerFileDeploymentSourceType.getInstance(), DockerComposeDeploymentSourceType.getInstance())
        for (source in sources) {
            @Suppress("UNCHECKED_CAST")
            val profile = type.configurationFactories.map { it.createTemplateConfiguration(project) }
                .filterIsInstance<DeployToServerRunConfiguration<*, *>>()
                .first { it.deploymentSource?.type == source } as
                DeployToServerRunConfiguration<ServerConfiguration, DockerDeploymentConfiguration>
            val configuration = DockerDeploymentConfiguration()
            profile.deploymentConfiguration = configuration
            configuration.apply {
                envVars = mutableListOf(DockerEnvVarImpl("KEY", "local"))
                services = mutableListOf("api", "worker")
                isUpDetach = true
                showCommandPreview = true
            }
            for (executor in listOf(DefaultRunExecutor.getRunExecutorInstance(), DefaultDebugExecutor.getDebugExecutorInstance())) {
                val launch = runThroughRunner(runner, profile, executor)
                val copy = (launch.runProfile as DeployToServerRunConfiguration<*, *>).deploymentConfiguration as DockerDeploymentConfiguration
                assertNotSame(configuration, copy)
                assertEquals("dev", copy.envVars.first { it.name == "DOPPLER_CONFIG" }.value)
                assertEquals("local", copy.envVars.first { it.name == "KEY" }.value)
                assertFalse(copy.showCommandPreview)
                assertEquals(configuration.services, copy.services)
                assertTrue(copy.isUpDetach)
            }
            assertEquals(1, configuration.envVars.size)
            assertTrue(configuration.showCommandPreview)
        }
    }

    fun `test Docker XML validation failures cannot expose environment values`() {
        val type = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.first { it.id == "docker-deploy" }
        @Suppress("UNCHECKED_CAST")
        val profile = type.configurationFactories.first().createTemplateConfiguration(project) as
            DeployToServerRunConfiguration<ServerConfiguration, DockerDeploymentConfiguration>
        profile.deploymentConfiguration = DockerDeploymentConfiguration()
        Files.createFile(directory.resolve("xml-control"))
        try {
            runThroughRunner(DopplerDockerEnvironmentRunner(), profile, DefaultRunExecutor.getRunExecutorInstance())
            fail("Invalid XML characters must abort Docker injection")
        } catch (error: ExecutionException) {
            assertTrue(error.message.orEmpty().startsWith("Could not prepare the Docker launch environment"))
            assertFalse(error.message.orEmpty().contains("synthetic"))
            assertNull(error.cause)
        }
    }

    private fun runThroughRunner(runner: DopplerEnvironmentRunner, profile: RunConfiguration, executor: Executor): ExecutionEnvironment {
        var received: ExecutionEnvironment? = null
        val delegate = object : ProgramRunner<RunnerSettings> {
            override fun getRunnerId(): String = "SyntheticDelegate"
            override fun canRun(executorId: String, profile: RunProfile): Boolean = true
            override fun execute(environment: ExecutionEnvironment) { received = environment }
        }
        val disposable = Disposer.newDisposable()
        try {
            ExtensionTestUtil.maskExtensions(ProgramRunner.PROGRAM_RUNNER_EP, listOf(delegate), disposable)
            assertTrue(runner.canRun(executor.id, profile))
            val settings = RunManager.getInstance(project).createConfiguration(profile, checkNotNull(profile.factory))
            val original = ExecutionEnvironmentBuilder(project, executor).runnerAndSettings(delegate, settings).executionId(42).build()
            background { runner.execute(original) }
            val launch = checkNotNull(received)
            assertEquals(42L, launch.executionId)
            assertSame(delegate, launch.runner)
            assertSame(settings, launch.runnerAndConfigurationSettings)
            assertSame(profile, settings.configuration)
            return launch
        } finally { Disposer.dispose(disposable) }
    }

    private fun assertSafeFailure() {
        try {
            background { service.mergeEnvironment(emptyMap()) }
            fail("Expected injection failure")
        } catch (error: ExecutionException) {
            assertFalse(error.message.orEmpty().contains("resolved"))
            assertNull(error.cause)
        }
    }

    private fun environment(): ExecutionEnvironment {
        val java = ApplicationConfigurationType.getInstance().configurationFactories.first().createTemplateConfiguration(project)
        return ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), java).build()
    }

    private fun <T> background(action: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().executeOnPooledThread { result = runCatching(action) }.get(30, TimeUnit.SECONDS)
        return result!!.getOrThrow()
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args) } as T
}

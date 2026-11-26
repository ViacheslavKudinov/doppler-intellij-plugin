package com.vk.doppler.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Executor

private const val PARALLELISM = 4

/** Secret name index bound to the login scope of one IDE project. */
@Service(Service.Level.PROJECT)
class SecretReferenceService(
    private val project: Project,
) : Disposable {
    private val index =
        SecretNameIndex(
            loader =
                DopplerSecretNamesLoader(
                    DopplerServiceNamesApi(project),
                    AppExecutorUtil.createBoundedApplicationPoolExecutor("Doppler secret names", PARALLELISM),
                ),
            executor =
                Executor { command ->
                    ProgressManager.getInstance().run(
                        object : Task.Backgroundable(project, "Loading Doppler secret names…", true) {
                            override fun run(indicator: ProgressIndicator) {
                                command.run()
                            }
                        },
                    )
                },
        )

    internal fun namesFor(
        authContextId: String,
        onChanged: () -> Unit,
    ): SecretNames = index.namesFor(authContextId, onChanged)

    internal fun isLoading(): Boolean = index.isLoading()

    internal fun cachedNamesFor(authContextId: String): SecretNames = index.cachedNamesFor(authContextId)

    /** Keeps the index current for a config whose names are already known. */
    fun update(
        authContextId: String,
        projectSlug: String,
        configName: String,
        names: Set<String>,
    ) = index.update(authContextId, projectSlug, configName, names)

    /** Makes the next completion refresh the account-wide names. */
    fun invalidate() = index.invalidate()

    /** Drops account metadata and prevents pending loads from restoring it after sign-out. */
    fun clear() = index.clear()

    override fun dispose() = index.dispose()

    companion object {
        fun getInstance(project: Project): SecretReferenceService = project.service()
    }
}

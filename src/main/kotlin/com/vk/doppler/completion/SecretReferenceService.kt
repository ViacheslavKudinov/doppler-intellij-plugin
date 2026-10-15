package com.vk.doppler.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Executor

private const val PARALLELISM = 4

/** Application-wide holder of the secret name index, shared by every open IDE project. */
@Service(Service.Level.APP)
class SecretReferenceService : Disposable {

    private val index = SecretNameIndex(
        loader = DopplerSecretNamesLoader(
            DopplerServiceNamesApi,
            AppExecutorUtil.createBoundedApplicationPoolExecutor("Doppler secret names", PARALLELISM),
        ),
        executor = Executor { command ->
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(null, "Loading Doppler secret names…", true) {
                    override fun run(indicator: ProgressIndicator) {
                        command.run()
                    }
                },
            )
        },
    )

    internal fun namesFor(authContextId: String, onChanged: () -> Unit): SecretNames =
        index.namesFor(authContextId, onChanged)

    internal fun isLoading(): Boolean = index.isLoading()

    /** Keeps the index current for a config whose names are already known. */
    fun update(authContextId: String, projectSlug: String, configName: String, names: Set<String>) =
        index.update(authContextId, projectSlug, configName, names)

    /** Makes the next completion refresh the account-wide names. */
    fun invalidate() = index.invalidate()

    override fun dispose() = index.dispose()

    companion object {
        fun getInstance(): SecretReferenceService = service()
    }
}

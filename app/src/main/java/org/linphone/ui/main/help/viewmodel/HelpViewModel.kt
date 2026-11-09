/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.help.viewmodel

import android.os.Build
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.VersionUpdateCheckResult
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LogCompartilhado

class HelpViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Help ViewModel]"

        private const val NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE = 6
    }

    val version = MutableLiveData<String>()

    val appVersion = MutableLiveData<String>()

    val sdkVersion = MutableLiveData<String>()

    val firebaseProjectId = MutableLiveData<String>()

    val checkUpdateAvailable = MutableLiveData<Boolean>()

    val uploadLogsAvailable = MutableLiveData<Boolean>()

    val logsUploadInProgress = MutableLiveData<Boolean>()

    val printLogsInLogcat = MutableLiveData<Boolean>()

    val developerSettingsEnabled = MutableLiveData<Boolean>()

    val canConfigFileBeViewed = MutableLiveData<Boolean>()

    val newVersionAvailableEvent: MutableLiveData<Event<Pair<String, String>>> by lazy {
        MutableLiveData()
    }

    val versionUpToDateEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val errorEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val debugLogsCleanedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val uploadDebugLogsFinishedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    /** Caminho do zip local com o log mascarado, pronto para o share sheet. */
    val logsCompartilharEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val logsCompartilharErroEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val uploadDebugLogsErrorEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val showConfigFileEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    private var versionClickCount: Int = 0

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onVersionUpdateCheckResultReceived(
            core: Core,
            result: VersionUpdateCheckResult,
            version: String?,
            url: String?
        ) {
            when (result) {
                VersionUpdateCheckResult.NewVersionAvailable -> {
                    Log.i("$TAG Update available, version [$version], url [$url]")
                    if (!version.isNullOrEmpty() && !url.isNullOrEmpty()) {
                        newVersionAvailableEvent.postValue(Event(Pair(version, url)))
                    }
                }
                VersionUpdateCheckResult.UpToDate -> {
                    Log.i("$TAG This version is up-to-date")
                    versionUpToDateEvent.postValue(Event(true))
                }
                else -> {
                    Log.e("$TAG Can't check for update, an error happened [$result]")
                    errorEvent.postValue(Event(true))
                }
            }
        }

        @WorkerThread
        override fun onLogCollectionUploadStateChanged(
            core: Core,
            state: Core.LogCollectionUploadState,
            info: String
        ) {
            Log.i("$TAG Logs upload state changed [$state]")
            if (state == Core.LogCollectionUploadState.Delivered) {
                logsUploadInProgress.postValue(false)
                uploadDebugLogsFinishedEvent.postValue(Event(info))
            } else if (state == Core.LogCollectionUploadState.NotDelivered) {
                logsUploadInProgress.postValue(false)
                uploadDebugLogsErrorEvent.postValue(Event(true))
            }
        }
    }

    init {
        val currentVersion = BuildConfig.VERSION_NAME
        version.value = currentVersion

        val versionCode = BuildConfig.VERSION_CODE
        val appGitDescribe = AppUtils.getString(R.string.linphone_app_version)
        val appBranch = AppUtils.getString(R.string.linphone_app_branch)
        appVersion.value = "$versionCode - $appGitDescribe ($appBranch)"

        sdkVersion.value = coreContext.sdkVersion
        logsUploadInProgress.value = false

        try {
            firebaseProjectId.value = FirebaseApp.getInstance().options.projectId
        } catch (e: Exception) {
            Log.e("$TAG Failed to get FirebaseApp instance: $e")
            firebaseProjectId.value = "unknown"
        }

        versionClickCount = if (corePreferences.showDeveloperSettings) {
            Log.i("$TAG Developer settings are already enabled")
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE
        } else {
            0
        }

        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)

            printLogsInLogcat.postValue(corePreferences.printLogsInLogcat)
            developerSettingsEnabled.postValue(corePreferences.showDeveloperSettings)
            checkUpdateAvailable.postValue(corePreferences.checkForUpdateServerUrl.isNotEmpty())
            uploadLogsAvailable.postValue(!core.logCollectionUploadServerUrl.isNullOrEmpty())
        }
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
        }
    }

    @UiThread
    fun versionClicked() {
        versionClickCount += 1
        Log.i("$TAG Version was clicked [$versionClickCount] times")
        when (versionClickCount) {
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE - 2 -> {
                showGreenToast(R.string.settings_developer_two_more_clicks_required_toast, R.drawable.gear)
            }
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE - 1 -> {
                showGreenToast(R.string.settings_developer_one_more_click_required_toast, R.drawable.gear)
            }
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE -> {
                showGreenToast(R.string.settings_developer_enabled_toast, R.drawable.gear)
                coreContext.postOnCoreThread {
                    Log.w("$TAG Enabling developer settings")
                    corePreferences.showDeveloperSettings = true
                    developerSettingsEnabled.postValue(true)
                }
            }
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE + 1 -> {
                showGreenToast(R.string.settings_developer_already_enabled_toast, R.drawable.gear)
            }
            else -> { }
        }
    }

    @UiThread
    fun cleanLogs() {
        coreContext.postOnCoreThread { core ->
            core.resetLogCollection()
            Log.i("$TAG Debug logs have been cleaned")
            debugLogsCleanedEvent.postValue(Event(true))
        }
    }

    /**
     * Nexus: em vez de subir o log para um servidor (o app não manda log para terceiros), gera um zip local com o
     * log do core mascarado ([LogCompartilhado]) e o fragmento abre o share sheet.
     */
    @UiThread
    fun shareLogs() {
        if (logsUploadInProgress.value == true) return
        logsUploadInProgress.value = true
        val context = coreContext.context
        val cabecalho = listOf(
            "${AppUtils.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "SDK: ${coreContext.sdkVersion}",
            "Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Gerado em: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())}",
            ""
        ).joinToString("\n")
        viewModelScope.launch {
            val caminho = withContext(Dispatchers.IO) {
                try {
                    val pasta = File(context.cacheDir, "logs")
                    // zip anterior só sai depois de 1 h (app que lê o anexo mais tarde, como upload na fila)
                    val limite = System.currentTimeMillis() - 3_600_000L
                    pasta.listFiles()?.filter { it.lastModified() < limite }?.forEach { it.delete() }
                    val arquivos = LogCompartilhado.dentroDoTeto(LogCompartilhado.arquivosDeLog(context.filesDir))
                    val carimbo = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                    val app = AppUtils.getString(R.string.app_name).lowercase(Locale.ROOT)
                        .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "app" }
                    val destino = File(pasta, "$app-logs-$carimbo.zip")
                    val linhas = LogCompartilhado.gerar(arquivos, destino, cabecalho) { isActive }
                    Log.i("$TAG Masked logs zipped: [${arquivos.size}] file(s), [$linhas] line(s)")
                    destino.absolutePath
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("$TAG Failed to build masked logs: $e")
                    null
                }
            }
            logsUploadInProgress.value = false
            if (caminho != null) {
                logsCompartilharEvent.value = Event(caminho)
            } else {
                logsCompartilharErroEvent.value = Event(true)
            }
        }
    }

    @UiThread
    fun checkForUpdate() {
        val currentVersion = version.value.orEmpty()
        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Checking for update using current version [$currentVersion]")
            core.checkForUpdate(currentVersion)
        }
    }

    @UiThread
    fun toggleLogcat() {
        val newValue = printLogsInLogcat.value == false
        coreContext.postOnCoreThread {
            corePreferences.printLogsInLogcat = newValue
            coreContext.updateLogcatEnabledSetting(newValue)
            Factory.instance().enableLogcatLogs(newValue)
            printLogsInLogcat.postValue(newValue)
        }
    }

    @UiThread
    fun showConfigFile() {
        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Dumping & displaying Core's config")
            val config = core.config.dump()
            val file = FileUtils.getFileStorageCacheDir(
                "linphonerc.txt",
                overrideExisting = true
            )
            viewModelScope.launch {
                if (FileUtils.dumpStringToFile(config, file)) {
                    Log.i("$TAG .linphonerc string saved as file in cache folder")
                    showConfigFileEvent.postValue(Event(file.absolutePath))
                } else {
                    Log.e("$TAG Failed to save .linphonerc string as file in cache folder")
                }
            }
        }
    }
}

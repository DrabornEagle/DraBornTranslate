// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.util.concurrent.Executors

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Downloading(val version: String, val percent: Int) : UpdateState
    data class Ready(val version: String, val file: File) : UpdateState
    data object Installing : UpdateState
    data class Confirm(val intent: Intent) : UpdateState
    data class Message(val text: String) : UpdateState
}

/** The download and its state survive Activity recreation. UI actions are taken on the foreground Activity. */
internal object AppUpdater {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val observers = linkedSetOf<(UpdateState) -> Unit>()
    private var activeSession: Int? = null
    private var permissionOpened = false
    var state: UpdateState = UpdateState.Idle
        private set

    fun observe(observer: (UpdateState) -> Unit) {
        observers.add(observer)
        observer(state)
    }

    fun remove(observer: (UpdateState) -> Unit) {
        observers.remove(observer)
    }

    fun check(context: Context) {
        when (state) {
            UpdateState.Idle, UpdateState.Current, is UpdateState.Message -> Unit
            else -> return
        }
        val app = context.applicationContext
        permissionOpened = false
        change(UpdateState.Checking)
        worker.execute {
            val file = File(app.cacheDir, "draborntranslate-update.apk")
            try {
                file.delete()
                val installed = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val release = GitHubUpdate.latest(installed.versionName.orEmpty())
                if (release == null) {
                    main.post { change(UpdateState.Current) }
                    return@execute
                }
                if ("arm64-v8a" !in Build.SUPPORTED_ABIS) throw UpdateException("Güncel APK, ARM64 Android telefonlar içindir.")
                if (app.cacheDir.usableSpace < release.size) throw UpdateException("APK’yı indirmek için yeterli alan yok.")
                main.post { change(UpdateState.Downloading(release.version, 0)) }
                GitHubUpdate.download(release, file) { percent ->
                    main.post { change(UpdateState.Downloading(release.version, percent)) }
                }
                val archive = app.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                val installedSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
                if (archive == null || archive.packageName != app.packageName || archive.versionName != release.version ||
                    archive.longVersionCode <= installed.longVersionCode || installedSigners.isEmpty() ||
                    archive.signingInfo?.apkContentsSigners?.toSet() != installedSigners
                ) {
                    throw UpdateException("APK’nın sürümü veya uygulama imzası doğrulanamadı.")
                }
                if (archive.applicationInfo?.minSdkVersion?.let { it > Build.VERSION.SDK_INT } == true) {
                    throw UpdateException("Bu güncelleme telefonundaki Android sürümünü desteklemiyor.")
                }
                main.post { change(UpdateState.Ready(release.version, file)) }
            } catch (error: Exception) {
                file.delete()
                val message = (error as? UpdateException)?.message ?: "Güncelleme alınamadı. İnternet bağlantını kontrol edip tekrar dene."
                main.post { change(UpdateState.Message(message)) }
            }
        }
    }

    fun continueInstall(activity: Activity, requested: Boolean = false) {
        when (val update = state) {
            is UpdateState.Ready -> {
                if (activity.packageManager.canRequestPackageInstalls()) {
                    stageInstall(activity.applicationContext, update.file)
                } else if (!permissionOpened || requested) {
                    permissionOpened = true
                    try {
                        activity.startActivity(Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.fromParts("package", activity.packageName, null),
                        ))
                    } catch (error: RuntimeException) {
                        update.file.delete()
                        change(UpdateState.Message("Android’in uygulama kurulum izni ekranı açılamadı."))
                    }
                }
            }
            is UpdateState.Confirm -> {
                change(UpdateState.Installing)
                try {
                    activity.startActivity(update.intent)
                } catch (error: RuntimeException) {
                    failInstall(activity, "Android kurulum ekranı açılamadı.")
                }
            }
            else -> Unit
        }
    }

    private fun stageInstall(app: Context, file: File) {
        change(UpdateState.Installing)
        val installer = app.packageManager.packageInstaller
        worker.execute {
            var sessionId: Int? = null
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                }
                val id = installer.createSession(params)
                sessionId = id
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                }
                main.post { commit(app, installer, id) }
            } catch (error: Exception) {
                sessionId?.let { runCatching { installer.abandonSession(it) } }
                main.post { change(UpdateState.Message("Android kurulumu hazırlanamadı. Depolama alanını kontrol edip tekrar dene.")) }
            } finally {
                file.delete()
            }
        }
    }

    private fun commit(app: Context, installer: PackageInstaller, id: Int) {
        activeSession = id
        try {
            val reply = Intent(app, UpdateInstallReceiver::class.java)
            val receiver = PendingIntent.getBroadcast(app, id, reply, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            installer.openSession(id).use { it.commit(receiver.intentSender) }
        } catch (error: Exception) {
            failInstall(app, "Android güncellemeyi başlatamadı. Tekrar dene.")
        }
    }

    @Suppress("DEPRECATION")
    fun installResult(context: Context, result: Intent) {
        if (result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != activeSession) return
        when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) change(UpdateState.Confirm(confirmation))
                else failInstall(context, "Android kurulum onayı alınamadı.")
            }
            PackageInstaller.STATUS_SUCCESS -> finishInstall("Güncelleme yüklendi.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> finishInstall("Kurulum iptal edildi. İstediğinde tekrar kontrol edebilirsin.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> finishInstall("Android kurulumu engelledi. Uygulama kurulum iznini kontrol et.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> finishInstall("Güncellemeyi kurmak için yeterli depolama alanı yok.")
            else -> finishInstall("Android güncellemeyi kuramadı. Tekrar dene.")
        }
    }

    private fun failInstall(context: Context, message: String) {
        activeSession?.let { runCatching { context.packageManager.packageInstaller.abandonSession(it) } }
        finishInstall(message)
    }

    private fun finishInstall(message: String) {
        activeSession = null
        change(UpdateState.Message(message))
    }

    private fun change(value: UpdateState) {
        state = value
        observers.toList().forEach { it(value) }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdater.installResult(context, intent)
}

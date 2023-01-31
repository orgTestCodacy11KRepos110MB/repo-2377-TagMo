package com.hiddenramblings.tagmo.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender.SendIntentException
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.hiddenramblings.tagmo.*
import com.hiddenramblings.tagmo.browser.BrowserActivity
import com.hiddenramblings.tagmo.eightbit.io.Debug
import com.hiddenramblings.tagmo.eightbit.net.JSONExecutor
import com.hiddenramblings.tagmo.eightbit.net.JSONExecutor.ResultListener
import com.hiddenramblings.tagmo.eightbit.os.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import kotlin.random.Random

class UpdateManager internal constructor(activity: BrowserActivity) {
    private var listener: CheckUpdateListener? = null
    private var listenerPlay: CheckPlayUpdateListener? = null
    private var appUpdateManager: AppUpdateManager? = null
    private val browserActivity: BrowserActivity = activity
    private var isUpdateAvailable = false

    private val scopeIO = CoroutineScope(Dispatchers.IO)

    init {
        if (!BuildConfig.WEAR_OS) {
            if (BuildConfig.GOOGLE_PLAY) configureManager(activity) else configureUpdates(activity)
        }
    }

    private fun configureManager(activity: BrowserActivity) {
        if (null == appUpdateManager) appUpdateManager = AppUpdateManagerFactory.create(activity)
        val appUpdateInfoTask = appUpdateManager!!.appUpdateInfo
        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo: AppUpdateInfo ->
            isUpdateAvailable = (appUpdateInfo.updateAvailability()
                    == UpdateAvailability.UPDATE_AVAILABLE
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE))
            if (isUpdateAvailable) listenerPlay?.onPlayUpdateFound(appUpdateInfo)
        }
    }

    private fun configureUpdates(activity: BrowserActivity) {
        scopeIO.launch(Dispatchers.IO) {
            if (Debug.isNewer(Build.VERSION_CODES.LOLLIPOP)) {
                activity.applicationContext.packageManager.packageInstaller.run {
                    mySessions.forEach {
                        try {
                            abandonSession(it.sessionId)
                        } catch (ignored: Exception) { }
                    }
                }
            }
            activity.externalCacheDir?.listFiles {
                    _: File?, name: String -> name.lowercase().endsWith(".apk")
            }?.forEach { if (!it.isDirectory) it.delete() }
            JSONExecutor(activity, TAGMO_GIT_API, "releases/tags/master")
                .setResultListener(object : ResultListener {
                    override fun onResults(result: String?) {
                        result?.let { parseUpdateJSON(it) }
                    }
                })
        }
    }

    fun installUpdateTask(apkUrl: String?) {
        if (null == apkUrl) return
        scopeIO.launch(Dispatchers.IO) {
            val apk = File(
                browserActivity.externalCacheDir,
                apkUrl.substring(apkUrl.lastIndexOf(File.separator) + 1)
            )
            try {
                URL(apkUrl).openStream().use { stream ->
                    FileOutputStream(apk).use {
                        stream.copyTo(it)
                    }
                }
                if (!apk.name.lowercase().endsWith(".apk")) apk.delete()
                browserActivity.run {
                    if (Debug.isNewer(Build.VERSION_CODES.N)) {
                        val apkUri = Storage.getFileUri(apk)
                        applicationContext.contentResolver.openInputStream(apkUri).use { apkStream ->
                            val session = applicationContext.packageManager.packageInstaller.run {
                                val params = PackageInstaller.SessionParams(
                                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                                )
                                openSession(createSession(params))
                            }
                            val document = DocumentFile.fromSingleUri(applicationContext, apkUri)
                                ?: throw IOException(browserActivity.getString(R.string.fail_invalid_size))
                            session.openWrite("NAME", 0, document.length()).use { sessionStream ->
                                apkStream?.copyTo(sessionStream)
                                session.fsync(sessionStream)
                            }
                            val pi = PendingIntent.getBroadcast(
                                applicationContext, Random.nextInt(),
                                Intent(applicationContext, UpdateReceiver::class.java),
                                if (Debug.isNewer(Build.VERSION_CODES.S))
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                                else PendingIntent.FLAG_UPDATE_CURRENT
                            )
                            session.commit(pi.intentSender)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                            .setDataAndType(
                                Storage.getFileUri(apk),
                                browserActivity.getString(R.string.mimetype_apk)
                            )
                            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, applicationInfo.packageName)
                        try {
                            startActivity(NFCIntent.getIntent(intent))
                        } catch (anf: ActivityNotFoundException) {
                            try {
                                startActivity(intent.setAction(Intent.ACTION_VIEW))
                            } catch (ignored: ActivityNotFoundException) { }
                        }
                    }
                }
            } catch (ex: SecurityException) {
                Debug.warn(ex)
            } catch (ex: IOException) {
                Debug.warn(ex)
            }
        }
    }

    fun installUpdateCompat(apkUrl: String?) {
        if (null == apkUrl) return
        if (Debug.isNewer(Build.VERSION_CODES.O)) {
            if (browserActivity.packageManager.canRequestPackageInstalls()) {
                installUpdateTask(apkUrl)
            } else {
                Preferences(browserActivity.applicationContext).downloadUrl(apkUrl)
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse(String.format(
                        "package:%s", browserActivity.packageName
                ))
                browserActivity.onRequestInstall.launch(intent)
            }
        } else {
            installUpdateTask(apkUrl)
        }
    }

    private fun parseUpdateJSON(result: String) {
        try {
            val jsonObject = JSONTokener(result).nextValue() as JSONObject
            val lastCommit = (jsonObject["name"] as String).substring(
                browserActivity.getString(R.string.tagmo).length + 1
            )
            val assets = jsonObject["assets"] as JSONArray
            val asset = assets[0] as JSONObject
            val downloadUrl = asset["browser_download_url"] as String
            isUpdateAvailable = BuildConfig.COMMIT != lastCommit
            if (isUpdateAvailable) listener?.onUpdateFound(downloadUrl)
        } catch (e: JSONException) {
            Debug.warn(e)
        }
    }

    fun downloadPlayUpdate(appUpdateInfo: AppUpdateInfo?) {
        try {
            appUpdateManager?.startUpdateFlowForResult( // Pass the intent that is returned by 'getAppUpdateInfo()'.
                appUpdateInfo!!,  // Or 'AppUpdateType.FLEXIBLE' for flexible updates.
                AppUpdateType.IMMEDIATE,  // The current activity making the update request.
                browserActivity,  // Include a request code to later monitor this update request.
                Random.nextInt()
            )
        } catch (ex: SendIntentException) {
            Debug.warn(ex)
        }
    }

    fun hasPendingUpdate(): Boolean {
        return isUpdateAvailable
    }

    fun setUpdateListener(listener: CheckUpdateListener?) {
        this.listener = listener
    }

    interface CheckUpdateListener {
        fun onUpdateFound(downloadUrl: String?)
    }

    fun setPlayUpdateListener(listenerPlay: CheckPlayUpdateListener?) {
        this.listenerPlay = listenerPlay
    }

    interface CheckPlayUpdateListener {
        fun onPlayUpdateFound(appUpdateInfo: AppUpdateInfo?)
    }

    companion object {
        private const val TAGMO_GIT_API = "https://api.github.com/repos/HiddenRamblings/TagMo/"
    }
}
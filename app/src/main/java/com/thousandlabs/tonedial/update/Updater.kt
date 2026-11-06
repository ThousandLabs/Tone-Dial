package com.thousandlabs.tonedial.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.thousandlabs.tonedial.BuildConfig
import com.thousandlabs.tonedial.data.UserStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mises à jour intégrées : vérifie la dernière version publiée sur GitHub,
 * la télécharge et lance l'installation. Ne bloque jamais l'ouverture de l'appli :
 * sans connexion, rien ne se passe.
 */
object Updater {

    private const val API_URL = "https://api.github.com/repos/ThousandLabs/Tone-Dial/releases/latest"
    private const val AUTO_KEY = "update_auto"
    private const val LAST_CHECK_KEY = "update_last_check_min"
    private const val DOWNLOADED_KEY = "update_downloaded_code"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String) : State
        data class Downloading(val release: Available, val progress: Float) : State
        data class Installing(val release: Available) : State
        data class Error(val message: String, val release: Available? = null, val retryInstall: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _bannerDismissed = MutableStateFlow(false)
    val bannerDismissed: StateFlow<Boolean> = _bannerDismissed.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun autoEnabled(): Boolean = UserStore.getBool(AUTO_KEY, true)
    fun setAutoEnabled(on: Boolean) = UserStore.putBool(AUTO_KEY, on)

    fun dismissBanner() {
        _bannerDismissed.value = true
    }

    /** Vérification automatique au lancement, au plus toutes les 30 minutes. */
    fun autoCheck() {
        if (!autoEnabled()) return
        val nowMin = (System.currentTimeMillis() / 60_000).toInt()
        if (nowMin - UserStore.getInt(LAST_CHECK_KEY, 0) < 30 && _state.value !is State.Available) return
        check(silent = true)
    }

    fun check(silent: Boolean = false) {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return
        if (!silent) _state.value = State.Checking
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetchLatest() } }
            UserStore.putInt(LAST_CHECK_KEY, (System.currentTimeMillis() / 60_000).toInt())
            result.onSuccess { latest ->
                _state.value = if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
                    _bannerDismissed.value = false
                    latest
                } else State.UpToDate
            }.onFailure {
                _state.value = if (silent) State.Idle else State.Error("Pas de connexion. Réessayez plus tard.")
            }
        }
    }

    private fun fetchLatest(): State.Available? {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ToneDial-Updater")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name") // ex. "v1.4"
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url"); break
                }
            }
            return State.Available(code, tag.removePrefix("v"), json.optString("body").trim(), url ?: return null)
        } finally {
            conn.disconnect()
        }
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Télécharge puis installe la version disponible. */
    fun downloadAndInstall(context: Context) {
        val release = when (val s = _state.value) {
            is State.Available -> s
            is State.Error -> s.release ?: return
            else -> return
        }
        val app = context.applicationContext
        val file = File(app.cacheDir, "update.apk")
        val alreadyDownloaded = file.exists() && file.length() > 0 &&
            UserStore.getInt(DOWNLOADED_KEY, -1) == release.versionCode
        _state.value = if (alreadyDownloaded) State.Installing(release) else State.Downloading(release, 0f)
        scope.launch {
            if (!alreadyDownloaded) {
                UserStore.putInt(DOWNLOADED_KEY, -1)
                val ok = runCatching {
                    withContext(Dispatchers.IO) { download(release.apkUrl, file) { p -> _state.value = State.Downloading(release, p) } }
                }.isSuccess
                if (!ok) {
                    file.delete()
                    _state.value = State.Error("Le téléchargement a échoué. Vérifiez la connexion.", release)
                    return@launch
                }
                UserStore.putInt(DOWNLOADED_KEY, release.versionCode)
            }
            _state.value = State.Installing(release)
            installStartedAt = System.currentTimeMillis()
            val installed = runCatching { withContext(Dispatchers.IO) { install(app, file) } }
            installed.onFailure {
                _state.value = State.Error("L'installation n'a pas pu démarrer.", release)
            }
        }
    }

    private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ToneDial-Updater")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastReport = 0L
            target.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 128 * 1024) {
                            lastReport = done
                            val p = (done.toFloat() / total).coerceIn(0f, 1f)
                            scope.launch { onProgress(p) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) error("Téléchargement incomplet")
        } finally {
            conn.disconnect()
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        // abandonne les tentatives précédentes restées en suspens
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("ToneDial.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private var installStartedAt = 0L

    /**
     * Appelé quand l'utilisateur revient dans l'appli. Si l'installation était en attente,
     * la fenêtre d'Android a été fermée sans confirmation : on propose de relancer.
     */
    fun onAppResumed(context: Context) {
        cleanupIfInstalled(context)
        val s = _state.value
        if (s is State.Installing && System.currentTimeMillis() - installStartedAt > 1500) {
            _state.value = State.Error("L'installation n'a pas été confirmée.", s.release, retryInstall = true)
        }
    }

    /** Supprime l'ancien fichier téléchargé une fois la mise à jour installée. */
    private fun cleanupIfInstalled(context: Context) {
        val code = UserStore.getInt(DOWNLOADED_KEY, -1)
        if (code in 0..BuildConfig.VERSION_CODE) {
            runCatching { File(context.cacheDir, "update.apk").delete() }
            UserStore.putInt(DOWNLOADED_KEY, -1)
        }
    }

    internal fun onInstallFailed(message: String) {
        val release = when (val s = _state.value) {
            is State.Installing -> s.release
            is State.Downloading -> s.release
            is State.Available -> s
            else -> null
        }
        _state.value = State.Error(message, release, retryInstall = release != null)
    }
}

/** Reçoit le résultat de l'installation et affiche la confirmation d'Android si nécessaire. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Updater.onInstallFailed("Impossible d'afficher la confirmation d'installation.") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // l'appli est remplacée par la nouvelle version
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.onInstallFailed("Mise à jour annulée.")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Updater.onInstallFailed("Échec de l'installation" + (msg?.let { " : $it" } ?: "."))
            }
        }
    }
}

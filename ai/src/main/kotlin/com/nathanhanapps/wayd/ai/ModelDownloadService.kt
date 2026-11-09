package com.nathanhanapps.wayd.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CHANNEL = "model_download"
private const val NOTIF_ID = 7
private const val ACTION_CANCEL = "com.nathanhanapps.wayd.ai.CANCEL"
private const val EXTRA_MODEL = "model"
private const val EXTRA_URL = "url"

/** Keeps a model download alive while the app is in the background. Start and stop it with [ModelDownloads]. */
@AndroidEntryPoint
class ModelDownloadService : Service() {
    @Inject lateinit var models: ModelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                stopSelf()
            }
            else -> {
                val spec = ModelCatalog.all.firstOrNull { it.id == intent?.getStringExtra(EXTRA_MODEL) }
                val url = intent?.getStringExtra(EXTRA_URL)
                if (spec == null || url.isNullOrBlank() || job?.isActive == true) {
                    if (job?.isActive != true) stopSelf()
                } else {
                    startForegroundCompat(notification(spec, 0, indeterminate = true, verifying = false))
                    job = scope.launch {
                        val progress = launch {
                            models.states.collect { s ->
                                when (val st = s[spec.id]) {
                                    is ModelState.Downloading -> notify(notification(spec, (st.doneBytes * 100 / st.totalBytes.coerceAtLeast(1)).toInt(), false, false))
                                    ModelState.Verifying -> notify(notification(spec, 100, false, verifying = true))
                                    else -> {}
                                }
                            }
                        }
                        models.download(spec, url)
                        progress.cancel()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ai_dl_channel), NotificationManager.IMPORTANCE_LOW))
        runCatching { startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            .onFailure { runCatching { nm.notify(NOTIF_ID, n) } }
    }

    private fun notify(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    private fun notification(spec: ModelSpec, percent: Int, indeterminate: Boolean, verifying: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.ai_dl_title, spec.displayName))
            .setContentText(if (verifying) getString(R.string.ai_dl_verifying) else getString(R.string.ai_dl_progress, percent))
            .setProgress(100, percent, indeterminate || verifying)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.ai_dl_cancel), cancel)
            .build()
    }

    companion object Intents {
        fun start(context: Context, spec: ModelSpec, url: String): Intent =
            Intent(context, ModelDownloadService::class.java).putExtra(EXTRA_MODEL, spec.id).putExtra(EXTRA_URL, url)

        fun cancel(context: Context): Intent = Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL)
    }
}

/** Entry points for the UI. */
object ModelDownloads {
    fun start(context: Context, spec: ModelSpec, url: String) {
        context.startForegroundService(ModelDownloadService.start(context, spec, url))
    }

    fun cancel(context: Context) {
        context.startService(ModelDownloadService.cancel(context))
    }
}

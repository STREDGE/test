package samm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * После установки запускает игру и ждёт сигнала от моста (status.properties + heartbeat).
 *  - нет сигнала за [START_TIMEOUT_MS] → «Игра не запустилась»;
 *  - игра стартовала, но AML не загрузился → предупреждение;
 *  - стартовала и почти сразу пропал heartbeat / есть crash.txt → «Игра вылетела».
 */
class LaunchWatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var launchedAt = 0L
    private var started = false
    private var reported = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifications.builder(this, "Проверяю запуск игры…", "Ждём сигнала от игры до ${START_TIMEOUT_MS / 1000} с").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.ID_WATCH, n)
        }
        started = false
        reported = false
        launchedAt = intent?.getLongExtra(EXTRA_LAUNCHED_AT, 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis()
        handler.postDelayed(::poll, 4000)
        return START_NOT_STICKY
    }

    private fun poll() {
        if (reported) return
        val now = System.currentTimeMillis()
        val status = try {
            BridgeClient(this).status()
        } catch (e: Exception) {
            Events.post(this, "Мост пока не отвечает: ${e.message}")
            null
        }
        if (status != null && status.startedAt != null && status.startedAt >= launchedAt - 2000) {
            if (!started) {
                started = true
                Events.post(this, "Игра запустилась (AML: ${status.aml ?: "?"})")
                if (status.aml == "error") {
                    finish("Игра запустилась, но AML не загрузился", status.amlError ?: "", error = true)
                    return
                }
            }
            val crash = status.crash
            val hbAge = status.heartbeatAt?.let { status.now - it } ?: Long.MAX_VALUE
            if (crash != null) {
                finish("Игра вылетела", crash.lineSequence().take(6).joinToString("\n"), error = true)
                return
            }
            if (hbAge > HEARTBEAT_STALE_MS && now - launchedAt < CRASH_WINDOW_MS) {
                finish("Игра закрылась сразу после запуска", "Похоже на вылет. Отправьте лог из менеджера.", error = true)
                return
            }
            if (now - launchedAt >= CRASH_WINDOW_MS) {
                val aml = if (status.aml == "loaded") "AML загружен ✔" else "режим проверки (без AML)"
                finish("Игра работает", "Запуск после патча прошёл успешно, $aml.", error = false)
                return
            }
        } else if (now - launchedAt > START_TIMEOUT_MS) {
            finish(
                "Игра не запустилась",
                "За ${START_TIMEOUT_MS / 1000} с игра не подала сигнал. Возможно, она не работает после переподписи. " +
                    "Можно вернуть оригинал из Google Play.",
                error = true,
            )
            return
        }
        handler.postDelayed(::poll, 3000)
    }

    private fun finish(title: String, text: String, error: Boolean) {
        reported = true
        Events.post(this, "$title. $text")
        Notifications.show(this, Notifications.ID_RESULT, title, text, error, withRestore = error)
        Prefs.setLastLaunch(this, title, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val START_TIMEOUT_MS = 60_000L
        const val CRASH_WINDOW_MS = 45_000L
        const val HEARTBEAT_STALE_MS = 12_000L

        const val EXTRA_LAUNCHED_AT = "launchedAt"

        /** Вызывать из видимой Activity: запускает игру и следит за ней. */
        fun launchAndWatch(activity: android.app.Activity): Boolean {
            val launch = activity.packageManager.getLaunchIntentForPackage(Game.PACKAGE) ?: return false
            val t = System.currentTimeMillis()
            activity.startActivity(launch)
            val i = Intent(activity, LaunchWatchService::class.java).putExtra(EXTRA_LAUNCHED_AT, t)
            activity.startForegroundService(i)
            return true
        }
    }
}

object Notifications {
    private const val CHANNEL = "samm"
    const val ID_WATCH = 1
    const val ID_RESULT = 2
    const val ID_INSTALL = 3

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "SA Mod Manager", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }

    fun builder(ctx: Context, title: String, text: String, openIntent: Intent? = null): Notification.Builder {
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, openIntent ?: Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        return b.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    fun show(ctx: Context, id: Int, title: String, text: String, error: Boolean, withRestore: Boolean = false, open: Intent? = null) {
        val b = builder(ctx, title, text, open)
        if (error) b.setSmallIcon(android.R.drawable.stat_notify_error)
        if (withRestore) {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Game.PACKAGE}"))
            val restore = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_RESTORE)
            b.addAction(Notification.Action.Builder(null, "Вернуть оригинал", PendingIntent.getActivity(ctx, 1, restore, PendingIntent.FLAG_IMMUTABLE)).build())
            b.addAction(Notification.Action.Builder(null, "Google Play", PendingIntent.getActivity(ctx, 2, market, PendingIntent.FLAG_IMMUTABLE)).build())
            val log = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SHARE_LOG)
            b.addAction(Notification.Action.Builder(null, "Отправить лог", PendingIntent.getActivity(ctx, 3, log, PendingIntent.FLAG_IMMUTABLE)).build())
        }
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, b.build())
        } catch (_: SecurityException) {
            // Нет разрешения на уведомления (Android 13+) — сообщение всё равно есть в логе на экране.
        }
    }
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("samm", Context.MODE_PRIVATE)
    fun setLastLaunch(ctx: Context, text: String, error: Boolean) =
        p(ctx).edit().putString("lastLaunch", text).putBoolean("lastLaunchError", error).putLong("lastLaunchAt", System.currentTimeMillis()).apply()

    fun lastLaunch(ctx: Context): String? = p(ctx).getString("lastLaunch", null)

    fun setLaunchPending(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("launchPending", v).apply()
    fun launchPending(ctx: Context): Boolean = p(ctx).getBoolean("launchPending", false)
}

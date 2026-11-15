package samm.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import java.io.File

/** Удаление оригинала и установка пропатченной игры (несколько split-APK одной сессией). */
object Installer {
    const val ACTION_STATUS = "samm.app.INSTALL_STATUS"

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Системный диалог удаления игры. */
    @Suppress("DEPRECATION")
    fun requestUninstall(ctx: Context) {
        ctx.startActivity(
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${Game.PACKAGE}"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(ctx: Context, apks: List<File>) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(Game.PACKAGE)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            for (apk in apks) {
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** Результат установки: подтверждение пользователем, успех или ошибка. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(ctx::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Events.post(ctx, "Игра установлена. Вернитесь в менеджер — он запустит игру и проверит её.")
                Prefs.setLaunchPending(ctx, true)
                val open = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_LAUNCH)
                Notifications.show(ctx, Notifications.ID_INSTALL, "Игра установлена", "Нажмите, чтобы запустить и проверить", error = false, open = open)
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $status"
                val hint = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    " — сначала удалите установленную игру (кнопка «Удалить оригинал»)."
                } else {
                    ""
                }
                Events.post(ctx, "Установка не удалась: $msg$hint")
                Notifications.show(ctx, Notifications.ID_INSTALL, "Установка не удалась", msg + hint, error = true)
            }
        }
    }
}

/** Простая шина сообщений для экрана (лог действий). */
object Events {
    private val listeners = mutableListOf<(String) -> Unit>()
    private val history = ArrayDeque<String>()

    @Synchronized
    fun post(@Suppress("UNUSED_PARAMETER") ctx: Context?, msg: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}  $msg"
        history.addLast(line)
        while (history.size > 300) history.removeFirst()
        listeners.toList().forEach { it(line) }
    }

    @Synchronized
    fun subscribe(l: (String) -> Unit): List<String> {
        listeners += l
        return history.toList()
    }

    @Synchronized
    fun unsubscribe(l: (String) -> Unit) {
        listeners -= l
    }

    @Synchronized
    fun dump(): String = history.joinToString("\n")
}

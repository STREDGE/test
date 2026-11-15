package samm.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import samm.patcher.PatchMode
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var info: TextView
    private lateinit var logView: TextView
    private lateinit var buttons: List<Button>
    private lateinit var btnAml: Button
    private var busy = false

    private val listener: (String) -> Unit = { line -> main.post { appendLog(line) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        Events.subscribe(listener).forEach(::appendLog)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        Events.unsubscribe(listener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        continuePendingInstall()
        launchIfPending()
    }

    /** После установки: вернуть OBB, запустить игру и следить за ней. */
    private fun launchIfPending() {
        if (!Prefs.launchPending(this) || busy) return
        if (Game.find(this) == null) return
        Prefs.setLaunchPending(this, false)
        runBusy("Готовлю первый запуск…") {
            if (Obb.hasAllFilesAccess()) {
                val n = Obb.restore { Events.post(this, it) }
                if (n > 0) Events.post(this, "OBB на месте ($n шт.)")
            }
            main.post {
                if (!LaunchWatchService.launchAndWatch(this)) Events.post(this, "Не удалось запустить игру")
            }
        }
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            ACTION_RESTORE -> restoreOriginal()
            ACTION_SHARE_LOG -> shareLog()
            ACTION_LAUNCH -> Prefs.setLaunchPending(this, true)
        }
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "SA Mod Manager"; textSize = 22f; setTypeface(typeface, Typeface.BOLD) })
        info = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(info)

        fun button(title: String, action: () -> Unit) = Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
            root.addView(this)
        }
        val b1 = button("1. Выгрузить libGTASA.so в «Загрузки»") { exportLib() }
        val b2 = button("2. Проверить запуск после переподписи (без модов)") { startPatch(PatchMode.TRIAL) }
        btnAml = button("3. Установить AML + CLEO") { startPatch(PatchMode.AML) }
        val bMods = button("Моды: CLEO, AML, gta3.img…") { startActivity(Intent(this, ModsActivity::class.java)) }
        val b4 = button("Вернуть оригинал (удалить пропатченную игру)") { restoreOriginal() }
        val b5 = button("Разрешить доступ к файлам (для бэкапа OBB)") { Obb.requestAllFilesAccess(this) }
        val b6 = button("Отправить лог") { shareLog() }
        val b7 = button("Запустить игру с проверкой") { if (!LaunchWatchService.launchAndWatch(this)) Events.post(this, "Игра не установлена") }
        buttons = listOf(b1, b2, btnAml, bMods, b4, b5, b6, b7)

        root.addView(TextView(this).apply { text = "Журнал"; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, 0) })
        logView = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        root.addView(logView)
        return ScrollView(this).apply { addView(root) }
    }

    private fun appendLog(line: String) {
        logView.append(line + "\n")
    }

    private fun refresh() {
        val game = Game.find(this)
        val hasPayload = assets.list("payload")?.isNotEmpty() == true
        info.text = buildString {
            if (game == null) {
                appendLine("GTA SA не установлена.")
            } else {
                appendLine("GTA SA ${game.versionName} (код ${game.versionCode})")
                appendLine("APK: ${game.apks.size} шт., ABI: ${game.primaryAbi ?: "?"}")
                appendLine(if (game.patched) "Пропатчена менеджером ✔" else "Оригинал из Google Play")
            }
            appendLine("AML в этой сборке: ${if (hasPayload) "есть" else "ещё нет (ждём порт под вашу версию)"}")
            appendLine("Доступ к OBB: ${if (Obb.hasAllFilesAccess()) "есть" else "нет"}")
            Prefs.lastLaunch(this@MainActivity)?.let { appendLine("Последняя проверка: $it") }
        }
        btnAml.isEnabled = hasPayload && !busy
    }

    private fun runBusy(title: String, block: () -> Unit) {
        if (busy) return
        busy = true
        buttons.forEach { it.isEnabled = false }
        Events.post(this, title)
        io.execute {
            try {
                block()
            } catch (t: Throwable) {
                Events.post(this, "Ошибка: ${t.message ?: t}")
                android.util.Log.e("SAMM", title, t)
            } finally {
                main.post {
                    busy = false
                    buttons.forEach { it.isEnabled = true }
                    refresh()
                }
            }
        }
    }

    // ---------- действия ----------

    private fun exportLib() {
        val game = Game.find(this) ?: return Events.post(this, "Игра не установлена")
        runBusy("Выгружаю libGTASA.so…") {
            val path = Workflow(this) { Events.post(this, it) }.exportGameLib(game)
            obbReport()
            main.post {
                AlertDialog.Builder(this)
                    .setTitle("Готово")
                    .setMessage("Файл сохранён: $path\n\nРядом лежит .txt с информацией о версии. Пришлите оба файла.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun obbReport() {
        val obb = Obb.list()
        Events.post(
            this,
            when {
                obb == null && !Obb.hasAllFilesAccess() -> "OBB: нет доступа (нужно разрешение «Доступ ко всем файлам»)"
                obb == null -> "OBB: папки нет или её не прочитать"
                obb.isEmpty() -> "OBB: файлов нет"
                else -> "OBB: " + obb.joinToString { "${it.name} (${it.length() / 1024 / 1024} МБ)" }
            },
        )
    }

    private fun startPatch(mode: PatchMode) {
        val game = Game.find(this) ?: return Events.post(this, "Игра не установлена — сначала поставьте её из Google Play")
        if (game.patched) {
            return Events.post(this, "Игра уже пропатчена. Чтобы пропатчить заново: «Вернуть оригинал», поставить игру из Google Play и повторить.")
        }
        if (!Installer.canInstall(this)) {
            Events.post(this, "Разрешите менеджеру устанавливать приложения и нажмите кнопку ещё раз")
            Installer.requestInstallPermission(this)
            return
        }
        runBusy(if (mode == PatchMode.TRIAL) "Собираю пробную версию (без модов)…" else "Собираю игру с AML…") {
            val signed = Workflow(this) { Events.post(this, it) }.buildPatched(game, mode)
            obbReport()
            if (Obb.hasAllFilesAccess()) {
                val n = Obb.backup { Events.post(this, it) }
                if (n > 0) Events.post(this, "OBB сохранены в ${Obb.backupDir}")
            }
            PendingInstall.save(this, signed, mode)
            main.post { confirmUninstall(mode) }
        }
    }

    private fun confirmUninstall(mode: PatchMode) {
        AlertDialog.Builder(this)
            .setTitle("Удалить оригинал?")
            .setMessage(
                "Пропатченная игра подписана другим ключом, поэтому оригинал нужно удалить.\n\n" +
                    "• Сохранения и скачанные игрой файлы (Android/data) удалятся. Включите облачное сохранение в игре, если хотите их сохранить.\n" +
                    "• OBB-файлы ${if (Obb.hasAllFilesAccess()) "сохранены и вернутся автоматически" else "не сохранены (нет доступа к файлам)"}.\n" +
                    "• Вернуть оригинал можно в любой момент из Google Play.\n\n" +
                    "После удаления менеджер сам предложит установить ${if (mode == PatchMode.TRIAL) "пробную" else "пропатченную"} версию.",
            )
            .setPositiveButton("Удалить и продолжить") { _, _ -> Installer.requestUninstall(this) }
            .setNegativeButton("Отмена") { _, _ -> PendingInstall.clear(this) }
            .show()
    }

    private fun continuePendingInstall() {
        val pending = PendingInstall.load(this) ?: return
        if (busy) return
        val game = Game.find(this)
        if (game != null) return // ещё не удалили оригинал
        PendingInstall.clear(this)
        Events.post(this, "Оригинал удалён. Устанавливаю ${pending.second.name}…")
        runBusy("Установка…") { Installer.install(this, pending.first) }
    }

    private fun restoreOriginal() {
        AlertDialog.Builder(this)
            .setTitle("Вернуть оригинал")
            .setMessage("Пропатченная игра будет удалена, затем откроется Google Play, чтобы поставить оригинал.")
            .setPositiveButton("Продолжить") { _, _ ->
                PendingInstall.clear(this)
                if (Game.find(this) != null) Installer.requestUninstall(this)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Game.PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun shareLog() {
        val text = buildString {
            appendLine("SA Mod Manager ${BuildConfigCompat.versionName(this@MainActivity)}")
            appendLine("Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ABI ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine(info.text)
            try {
                val c = BridgeClient(this@MainActivity)
                if (c.available()) {
                    val s = c.status()
                    appendLine("--- status ---")
                    appendLine(s.raw ?: "нет")
                    s.crash?.let { appendLine("--- crash ---"); appendLine(it) }
                }
            } catch (e: Exception) {
                appendLine("Мост: ${e.message}")
            }
            appendLine("--- журнал ---")
            appendLine(Events.dump())
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Отправить лог"))
    }

    companion object {
        const val ACTION_RESTORE = "samm.app.RESTORE"
        const val ACTION_SHARE_LOG = "samm.app.SHARE_LOG"
        const val ACTION_LAUNCH = "samm.app.LAUNCH"
    }
}

/** Собранные APK ждут, пока пользователь удалит оригинал. */
object PendingInstall {
    private fun p(ctx: android.content.Context) = ctx.getSharedPreferences("samm", android.content.Context.MODE_PRIVATE)

    fun save(ctx: android.content.Context, apks: List<File>, mode: PatchMode) =
        p(ctx).edit().putString("pendingApks", apks.joinToString("|") { it.path }).putString("pendingMode", mode.name).apply()

    fun load(ctx: android.content.Context): Pair<List<File>, PatchMode>? {
        val s = p(ctx).getString("pendingApks", null) ?: return null
        val files = s.split('|').map(::File)
        if (files.any { !it.isFile }) return null
        return files to PatchMode.valueOf(p(ctx).getString("pendingMode", "TRIAL")!!)
    }

    fun clear(ctx: android.content.Context) = p(ctx).edit().remove("pendingApks").remove("pendingMode").apply()
}

package samm.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import samm.modcore.img.ImgArchive
import samm.modcore.mods.GameLayout
import samm.modcore.mods.ModArchive
import samm.modcore.mods.ModClassifier
import samm.modcore.mods.ModPlan
import samm.modcore.mods.ModTarget
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * Менеджер модов: папки CLEO / AML (mods, configs), установка из архива, IMG-архивы игры.
 * Работает только с пропатченной игрой — все файлы идут через мост внутри игры.
 */
class ModsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var bridge: BridgeClient
    private lateinit var titleView: TextView
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var section = Section.CLEO
    private var entries: List<BridgeClient.Entry> = emptyList()

    private enum class Section(val label: String, val dir: String) {
        CLEO("CLEO", GameLayout.CLEO_DIR),
        AML("AML-моды", GameLayout.AML_MODS_DIR),
        CONFIGS("Конфиги", GameLayout.AML_CONFIGS_DIR),
        IMG("IMG-архивы", ""),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = BridgeClient(this)
        setContentView(buildUi())
        if (!bridge.available()) {
            status.text = "Игра не пропатчена менеджером — моды ставятся только в пропатченную игру (кнопка 3 на главном экране)."
        } else {
            reload()
        }
    }

    private fun buildUi(): View {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        titleView = TextView(this).apply { textSize = 20f; setTypeface(typeface, Typeface.BOLD) }
        root.addView(titleView)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Section.entries.forEach { s ->
            tabs.addView(
                Button(this).apply {
                    text = s.label
                    isAllCaps = false
                    textSize = 12f
                    setOnClickListener { section = s; reload() }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        root.addView(tabs)
        root.addView(
            Button(this).apply {
                text = "Установить мод из архива (zip / 7z / rar)…"
                isAllCaps = false
                setOnClickListener { pickArchive() }
            },
        )
        status = TextView(this).apply { textSize = 13f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ -> onEntry(entries[pos]) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun bg(what: String, block: () -> Unit) {
        status.text = what
        io.execute {
            try {
                block()
            } catch (t: Throwable) {
                main.post { status.text = "Ошибка: ${t.message ?: t}" }
                Events.post(this, "Моды: ошибка «$what»: ${t.message}")
            }
        }
    }

    // ---------- списки ----------

    private fun reload() {
        titleView.text = section.label
        bg("Загрузка…") {
            val items = if (section == Section.IMG) findImgs() else bridge.list(section.dir).filter { !it.dir || section != Section.AML }
            main.post {
                entries = items
                list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items.map(::describe))
                status.text = when {
                    items.isNotEmpty() -> "${items.size} шт. Нажмите на файл для действий."
                    section == Section.IMG -> "IMG-архивы в папке игры не найдены. Возможно, в вашей версии модели лежат в OBB или в APK."
                    else -> "Пусто. Установите мод из архива."
                }
            }
        }
    }

    private fun describe(e: BridgeClient.Entry): String {
        val off = e.name.endsWith(GameLayout.DISABLED_SUFFIX)
        val name = if (section == Section.IMG) e.name else e.name.removeSuffix(GameLayout.DISABLED_SUFFIX)
        val size = if (e.dir) "папка" else humanSize(e.size)
        return (if (off) "⏸ " else "") + "$name  ·  $size"
    }

    /** Ищет *.img в папке игры (не глубже 4 уровней). */
    private fun findImgs(): List<BridgeClient.Entry> {
        val out = ArrayList<BridgeClient.Entry>()
        fun walk(path: String, depth: Int) {
            if (depth > 4) return
            for (e in bridge.list(path)) {
                val p = if (path.isEmpty()) e.name else "$path/${e.name}"
                if (e.dir && e.name != "samm" && e.name != "cache") walk(p, depth + 1)
                else if (!e.dir && e.name.endsWith(".img", ignoreCase = true)) out += e.copy(name = p)
            }
        }
        walk("", 0)
        return out
    }

    private fun onEntry(e: BridgeClient.Entry) {
        if (section == Section.IMG) return openImg(e.name)
        val path = "${section.dir}/${e.name}"
        val disabled = e.name.endsWith(GameLayout.DISABLED_SUFFIX)
        AlertDialog.Builder(this)
            .setTitle(e.name.removeSuffix(GameLayout.DISABLED_SUFFIX))
            .setItems(arrayOf(if (disabled) "Включить" else "Выключить", "Удалить")) { _, which ->
                bg("Выполняю…") {
                    when (which) {
                        0 -> {
                            val to = if (disabled) path.removeSuffix(GameLayout.DISABLED_SUFFIX) else path + GameLayout.DISABLED_SUFFIX
                            bridge.rename(path, to)
                            Events.post(this, "${if (disabled) "Включён" else "Выключен"}: ${e.name}")
                        }
                        1 -> {
                            bridge.delete(path)
                            Events.post(this, "Удалён: ${e.name}")
                        }
                    }
                    main.post { reload() }
                }
            }
            .show()
    }

    // ---------- установка из архива ----------

    private fun pickArchive() {
        if (!bridge.available()) return
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            REQ_ARCHIVE,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_ARCHIVE -> prepareInstall(uri)
            REQ_IMG_FILE -> pendingImg?.let { importIntoImg(it, listOf(uri)) }
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private fun prepareInstall(uri: Uri) {
        bg("Читаю архив…") {
            val name = displayName(uri)
            val copy = File(cacheDir, "mod-archive").apply { delete() }
            contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
            val plan = if (ModArchive.detect(copy) == ModArchive.Kind.UNKNOWN) {
                // Не архив — один файл (например, .csa или .so).
                ModClassifier.classify(listOf(name))
            } else {
                ModClassifier.classify(ModArchive.list(copy))
            }
            main.post { confirmInstall(name, copy, plan) }
        }
    }

    private fun confirmInstall(name: String, archive: File, plan: ModPlan) {
        val text = buildString {
            for (f in plan.files) {
                val where = when (f.target) {
                    ModTarget.IGNORE -> "пропуск"
                    ModTarget.UNKNOWN -> "❓ непонятно куда — пропуск"
                    ModTarget.IMG -> "→ IMG-архив (выберете ниже)"
                    ModTarget.GAME_FILE -> "→ ${f.destination} (замена файла игры!)"
                    else -> "→ ${f.destination}"
                }
                appendLine("${f.archivePath}  $where")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Установить «$name»?")
            .setMessage(text.ifBlank { "В архиве нет файлов." })
            .setPositiveButton("Установить") { _, _ -> install(archive, name, plan) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun install(archive: File, name: String, plan: ModPlan) {
        bg("Устанавливаю…") {
            val byPath = plan.files.associateBy { it.archivePath.replace('\\', '/').trimStart('/') }
            val imgFiles = HashMap<String, File>()
            var n = 0
            val handle = { path: String, input: java.io.InputStream ->
                val f = byPath[path.replace('\\', '/').trimStart('/')]
                val dest = f?.destination
                if (f != null && dest != null) {
                    when (f.target) {
                        ModTarget.CLEO, ModTarget.AML_MOD, ModTarget.AML_CONFIG, ModTarget.GAME_FILE -> {
                            if (f.target == ModTarget.GAME_FILE) backupGameFile(dest)
                            bridge.write(dest, input)
                            n++
                        }
                        ModTarget.IMG -> {
                            val tmp = File(cacheDir, "img-import/$dest").apply { parentFile!!.mkdirs() }
                            tmp.outputStream().use { input.copyTo(it) }
                            imgFiles[dest] = tmp
                        }
                        else -> Unit
                    }
                }
                Unit
            }
            if (ModArchive.detect(archive) == ModArchive.Kind.UNKNOWN) {
                archive.inputStream().use { handle(name, it) }
            } else {
                ModArchive.forEach(archive, handle)
            }
            Events.post(this, "Мод «$name»: установлено файлов — $n${if (imgFiles.isNotEmpty()) ", для IMG — ${imgFiles.size}" else ""}")
            main.post {
                if (imgFiles.isNotEmpty()) chooseImgFor(imgFiles) else reload()
            }
        }
    }

    /** Перед заменой файла игры сохраняем оригинал в samm/backup/ (один раз). */
    private fun backupGameFile(path: String) {
        val backup = "samm/backup/$path"
        try {
            val parent = path.substringBeforeLast('/', "")
            val exists = bridge.list(parent).any { it.name == path.substringAfterLast('/') }
            val backed = bridge.list(backup.substringBeforeLast('/')).any { it.name == path.substringAfterLast('/') }
            if (exists && !backed) bridge.read(path).use { bridge.write(backup, it) }
        } catch (e: Exception) {
            Events.post(this, "Не удалось сохранить оригинал $path: ${e.message}")
        }
    }

    // ---------- IMG ----------

    private var pendingImg: String? = null

    private fun chooseImgFor(files: Map<String, File>) {
        bg("Ищу IMG-архивы…") {
            val imgs = findImgs()
            main.post {
                if (imgs.isEmpty()) {
                    status.text = "IMG-архивы не найдены — файлы ${files.keys} не установлены."
                    return@post
                }
                val names = imgs.map { it.name }
                val preferred = names.indexOfFirst { it.substringAfterLast('/').equals("gta3.img", true) }.coerceAtLeast(0)
                AlertDialog.Builder(this)
                    .setTitle("В какой IMG положить ${files.size} файл(ов)?")
                    .setSingleChoiceItems(names.toTypedArray(), preferred, null)
                    .setPositiveButton("OK") { d, _ ->
                        val pos = (d as AlertDialog).listView.checkedItemPosition
                        importFiles(names[pos], files)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
    }

    private fun openImg(path: String) {
        bg("Открываю $path…") {
            val names = withImg(path, readOnly = true) { img -> img.entries.map { "${it.name}  ·  ${humanSize(it.sizeBytes)}" } }
            main.post { showImg(path, names) }
        }
    }

    private fun showImg(path: String, names: List<String>) {
        val filter = EditText(this).apply { hint = "Поиск по имени (например, infernus)" }
        val lv = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names.toMutableList())
        lv.adapter = adapter
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.clear()
                adapter.addAll(names.filter { it.contains(s.toString(), ignoreCase = true) })
            }
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(filter)
            addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * resources.displayMetrics.density).toInt()))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("$path (${names.size} файлов)")
            .setView(box)
            .setPositiveButton("Добавить/заменить файл…") { _, _ ->
                pendingImg = path
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMG_FILE)
            }
            .setNeutralButton("Восстановить оригинал") { _, _ -> restoreImg(path) }
            .setNegativeButton("Закрыть", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            val entryName = adapter.getItem(pos)!!.substringBefore("  ·")
            AlertDialog.Builder(this)
                .setTitle(entryName)
                .setItems(arrayOf("Извлечь в «Загрузки»", "Удалить из архива")) { _, which ->
                    dialog.dismiss()
                    if (which == 0) extractFromImg(path, entryName) else deleteFromImg(path, entryName)
                }
                .show()
        }
        dialog.show()
    }

    private fun importIntoImg(path: String, uris: List<Uri>) {
        bg("Готовлю файлы…") {
            val files = uris.associate { u ->
                val name = displayName(u)
                val tmp = File(cacheDir, "img-import/$name").apply { parentFile!!.mkdirs() }
                contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                name to tmp
            }
            main.post { importFiles(path, files) }
        }
    }

    private fun importFiles(path: String, files: Map<String, File>) {
        bg("Записываю в $path…") {
            ensureImgBackup(path)
            withImg(path, readOnly = false) { img ->
                files.forEach { (name, f) ->
                    f.inputStream().use { img.put(name, it, f.length()) }
                    Events.post(this, "IMG $path: ${if (name in img) "заменён/добавлен" else "добавлен"} $name")
                }
            }
            files.values.forEach { it.delete() }
            main.post { status.text = "Готово: ${files.keys.joinToString()} → $path"; reload() }
        }
    }

    private fun extractFromImg(path: String, name: String) {
        bg("Извлекаю $name…") {
            val data = withImg(path, readOnly = true) { it.read(name) }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(data) }
            main.post { status.text = "$name сохранён в «Загрузки»" }
        }
    }

    private fun deleteFromImg(path: String, name: String) {
        bg("Удаляю $name…") {
            ensureImgBackup(path)
            withImg(path, readOnly = false) { it.delete(name) }
            Events.post(this, "IMG $path: удалён $name")
            main.post { status.text = "$name удалён из $path" }
        }
    }

    private fun backupPath(path: String) = "samm/backup/$path"

    /** Полная копия IMG перед первым изменением. */
    private fun ensureImgBackup(path: String) {
        val b = backupPath(path)
        val exists = bridge.list(b.substringBeforeLast('/')).any { it.name == b.substringAfterLast('/') }
        if (exists) return
        Events.post(this, "Сохраняю оригинал $path (один раз)…")
        bridge.read(path).use { bridge.write(b, it) }
    }

    private fun restoreImg(path: String) {
        bg("Восстанавливаю $path…") {
            val b = backupPath(path)
            val exists = bridge.list(b.substringBeforeLast('/')).any { it.name == b.substringAfterLast('/') }
            if (!exists) {
                main.post { status.text = "Оригинал $path не сохранялся — архив не менялся менеджером." }
                return@bg
            }
            bridge.read(b).use { bridge.write(path, it) }
            Events.post(this, "IMG $path восстановлен из бэкапа")
            main.post { status.text = "$path восстановлен" }
        }
    }

    /** Открывает IMG внутри папки игры через дескриптор моста (/proc/self/fd/N — произвольный доступ). */
    private fun <T> withImg(path: String, readOnly: Boolean, block: (ImgArchive) -> T): T {
        bridge.openFd(path, if (readOnly) "r" else "rw").use { pfd ->
            val procPath = File("/proc/self/fd/${pfd.fd}")
            // Проверим, что ядро даёт открыть дескриптор по пути.
            RandomAccessFile(procPath, if (readOnly) "r" else "rw").close()
            return ImgArchive.open(procPath, readOnly).use(block)
        }
    }

    companion object {
        private const val REQ_ARCHIVE = 10
        private const val REQ_IMG_FILE = 11

        fun humanSize(b: Long): String = when {
            b >= 1024 * 1024 -> "%.1f МБ".format(b / 1024.0 / 1024.0)
            b >= 1024 -> "%.0f КБ".format(b / 1024.0)
            else -> "$b Б"
        }
    }
}

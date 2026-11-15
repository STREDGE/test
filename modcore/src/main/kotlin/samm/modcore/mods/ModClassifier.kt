package samm.modcore.mods

/**
 * Куда в папке игры (`Android/data/com.rockstargames.gtasa/`) кладутся файлы модов.
 * Пути относительные, разделитель — `/`.
 */
object GameLayout {
    const val PACKAGE = "com.rockstargames.gtasa"
    const val CLEO_DIR = "CLEO"
    const val AML_MODS_DIR = "mods"
    const val AML_CONFIGS_DIR = "configs"
    const val GAME_FILES_DIR = "files"
    const val TEXDB_DIR = "files/texdb"
    const val DISABLED_SUFFIX = ".disabled"
}

enum class ModTarget {
    /** CLEO-скрипт или файл рядом с ним (.csa, .csi, .fxt, .ini скрипта). */
    CLEO,
    /** Библиотека AML-мода (.so). */
    AML_MOD,
    /** Конфиг AML-мода. */
    AML_CONFIG,
    /** Модель/текстура/коллизия/анимация для импорта в IMG-архив. */
    IMG,
    /** Файл игры, который заменяется по пути (texdb, data/…dat и т. п.). Требует подтверждения. */
    GAME_FILE,
    /** Описание, картинки, ссылки — не устанавливаются. */
    IGNORE,
    /** Непонятно куда — показываем пользователю. */
    UNKNOWN,
}

/** Решение по одному файлу из архива мода. */
data class ModFile(
    val archivePath: String,
    val target: ModTarget,
    /** Путь назначения относительно папки игры; для IMG — имя файла внутри архива. */
    val destination: String?,
)

data class ModPlan(val files: List<ModFile>) {
    val installable: List<ModFile> get() = files.filter { it.destination != null && it.target != ModTarget.IGNORE && it.target != ModTarget.UNKNOWN }
    val unknown: List<ModFile> get() = files.filter { it.target == ModTarget.UNKNOWN }
    val needsImg: Boolean get() = files.any { it.target == ModTarget.IMG }
    val hasGameFiles: Boolean get() = files.any { it.target == ModTarget.GAME_FILE }
    val summary: Map<ModTarget, Int> get() = files.groupingBy { it.target }.eachCount()
}

/**
 * Раскладывает содержимое архива мода (обычно с libertycity.net) по папкам игры.
 */
object ModClassifier {

    private val CLEO_EXT = setOf("csa", "csi", "cs", "cm", "fxt", "scm")
    private val IMG_EXT = setOf("dff", "txd", "col", "ifp", "ipl")
    private val CONFIG_EXT = setOf("ini", "cfg", "json", "txt")
    private val DOC_EXT = setOf("txt", "md", "url", "html", "htm", "jpg", "jpeg", "png", "gif", "webp", "bmp", "pdf", "doc", "docx", "rtf", "mp4", "lnk")
    private val DOC_NAMES = setOf("readme", "read me", "readme_ru", "readme_en", "install", "инструкция", "установка", "license", "licence", "credits")
    private val GAME_DATA_EXT = setOf("dat", "ide", "cfg", "gxt", "zon")
    private val GAME_DATA_DIRS = setOf("data", "texdb", "audio", "models", "anim", "text", "scm")

    fun classify(paths: List<String>): ModPlan {
        val normalized = paths.map(::normalize).filter { it.isNotEmpty() && !it.endsWith("/") && !isJunk(it) }
        val root = commonRoot(normalized)
        return ModPlan(normalized.map { classifyOne(it, it.removePrefix(root)) })
    }

    private fun classifyOne(original: String, path: String): ModFile {
        val segments = path.split('/')
        val lowerSegs = segments.map { it.lowercase() }
        val name = segments.last()
        val lower = name.lowercase()
        val ext = lower.substringAfterLast('.', "")

        // 1. Явная структура внутри архива: .../cleo/..., .../mods/..., .../configs/..., .../texdb/...
        indexOfDir(lowerSegs, "cleo").let { i ->
            if (i >= 0) return ModFile(original, ModTarget.CLEO, "${GameLayout.CLEO_DIR}/" + segments.drop(i + 1).joinToString("/"))
        }
        indexOfDir(lowerSegs, "configs").let { i ->
            if (i >= 0) return ModFile(original, ModTarget.AML_CONFIG, "${GameLayout.AML_CONFIGS_DIR}/" + segments.drop(i + 1).joinToString("/"))
        }
        if (ext == "so") return ModFile(original, ModTarget.AML_MOD, "${GameLayout.AML_MODS_DIR}/$name")
        indexOfDir(lowerSegs, "mods").let { i ->
            if (i >= 0) return ModFile(original, ModTarget.AML_MOD, "${GameLayout.AML_MODS_DIR}/" + segments.drop(i + 1).joinToString("/"))
        }

        // 2. По расширению.
        if (ext in CLEO_EXT) return ModFile(original, ModTarget.CLEO, "${GameLayout.CLEO_DIR}/$name")
        if (ext in IMG_EXT) {
            return if (name.length < 24 && name.all { it.code in 0x21..0x7E }) {
                ModFile(original, ModTarget.IMG, name)
            } else {
                ModFile(original, ModTarget.UNKNOWN, null)
            }
        }
        if (isDoc(lower, ext, segments.size)) return ModFile(original, ModTarget.IGNORE, null)

        // 3. Файлы игры по пути (texdb, data/…dat ...).
        val gameDir = lowerSegs.dropLast(1).indexOfFirst { it in GAME_DATA_DIRS }
        if (gameDir >= 0) {
            return ModFile(original, ModTarget.GAME_FILE, "${GameLayout.GAME_FILES_DIR}/" + segments.drop(gameDir).joinToString("/"))
        }
        if (ext in GAME_DATA_EXT) return ModFile(original, ModTarget.GAME_FILE, "${GameLayout.GAME_FILES_DIR}/data/$name")

        // 4. Конфиг рядом с .so-модом (в корне архива).
        if (ext in CONFIG_EXT && segments.size == 1) return ModFile(original, ModTarget.AML_CONFIG, "${GameLayout.AML_CONFIGS_DIR}/$name")

        return ModFile(original, ModTarget.UNKNOWN, null)
    }

    private fun isDoc(lower: String, ext: String, depth: Int): Boolean {
        val base = lower.substringBeforeLast('.')
        if (DOC_NAMES.any { base.startsWith(it) }) return true
        if (ext in DOC_EXT && ext != "txt") return true
        // .txt в корне архива без структуры — почти всегда описание
        return ext == "txt" && depth <= 2
    }

    private fun indexOfDir(lowerSegs: List<String>, dir: String): Int = lowerSegs.dropLast(1).indexOfLast { it == dir }

    private fun normalize(p: String): String = p.replace('\\', '/').trimStart('/').let { s ->
        s.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/") + if (s.endsWith("/")) "/" else ""
    }

    private fun isJunk(p: String): Boolean {
        val l = p.lowercase()
        return l.startsWith("__macosx/") || l.contains("/__macosx/") || l.endsWith("/.ds_store") || l == ".ds_store" || l.endsWith("thumbs.db")
    }

    /** Общая папка-обёртка («Mod v1.2/…»), если все файлы лежат в ней и это не служебная папка. */
    private fun commonRoot(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        var root = ""
        while (true) {
            val firstSegs = paths.map { it.removePrefix(root).substringBefore('/', "") }
            val seg = firstSegs.first()
            if (seg.isEmpty() || firstSegs.any { it != seg }) return root
            if (seg.lowercase() in setOf("cleo", "mods", "configs", "texdb", "data", "files")) return root
            if (paths.any { it.removePrefix(root) == seg }) return root
            root += "$seg/"
        }
    }
}

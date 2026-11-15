package samm.patcher

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool

/**
 * Вставляет вызов `samm.bridge.Bridge.afterGameLibs()` в код игры.
 *
 * Лучшее место — сразу после `System.loadLibrary("GTASA")`: к этому моменту библиотека игры
 * загружена, и AML может к ней подцепиться. Если такой вызов не найден — в начало
 * `onCreate` главной Activity (к этому моменту статическая инициализация уже прошла).
 */
object DexPatcher {
    const val BRIDGE_PREFIX = "Lsamm/bridge/"
    const val BRIDGE_CLASS = "Lsamm/bridge/Bridge;"
    const val HOOK_METHOD = "afterGameLibs"

    private val HOOK_REF = ImmutableMethodReference(BRIDGE_CLASS, HOOK_METHOD, emptyList(), "V")

    enum class HookKind { AFTER_LOAD_LIBRARY, MAIN_ACTIVITY_ON_CREATE }

    data class Result(
        val dexName: String,
        val patchedDex: ByteArray,
        val className: String,
        val methodName: String,
        val kind: HookKind,
    )

    private data class Site(val dexName: String, val classType: String, val method: Method, val insertAt: Int, val kind: HookKind)

    fun load(bytes: ByteArray): DexBackedDexFile {
        val version = String(bytes, 4, 3, Charsets.US_ASCII).toIntOrNull() ?: 35
        return DexBackedDexFile(Opcodes.forDexVersion(version), bytes)
    }

    /**
     * @param dexes содержимое classes*.dex из base.apk (имя → байты)
     * @param mainActivity полное имя главной Activity из манифеста
     * @param gameLib имя библиотеки игры без lib/.so
     */
    fun patch(dexes: Map<String, ByteArray>, mainActivity: String?, gameLib: String = "GTASA"): Result {
        val parsed = dexes.mapValues { load(it.value) }
        if (parsed.values.any { d -> d.classes.any { it.type == BRIDGE_CLASS } }) {
            throw PatchException("Игра уже пропатчена (найден $BRIDGE_CLASS). Сначала верните оригинал.")
        }
        val site = findLoadLibrarySite(parsed, gameLib)
            ?: findOnCreateSite(parsed, mainActivity)
            ?: throw PatchException("Не нашёл места для загрузки AML: нет System.loadLibrary(\"$gameLib\") и onCreate у $mainActivity")

        val dex = parsed.getValue(site.dexName)
        val pool = DexPool(dex.opcodes)
        for (c in dex.classes) {
            pool.internClass(if (c.type == site.classType) rewriteClass(c, site) else c)
        }
        val store = MemoryDataStore()
        pool.writeTo(store)
        return Result(site.dexName, store.data, site.classType, site.method.name, site.kind)
    }

    /** Собрать отдельный dex из классов с префиксом [prefix] (наш мост из APK менеджера). */
    fun extractClasses(dexes: Collection<ByteArray>, prefix: String = BRIDGE_PREFIX): ByteArray {
        var opcodes: Opcodes? = null
        val picked = ArrayList<ClassDef>()
        for (bytes in dexes) {
            val d = load(bytes)
            val found = d.classes.filter { it.type.startsWith(prefix) }
            if (found.isNotEmpty() && opcodes == null) opcodes = d.opcodes
            picked += found
        }
        if (picked.none { it.type == BRIDGE_CLASS }) throw PatchException("В APK менеджера нет класса $BRIDGE_CLASS")
        val pool = DexPool(opcodes ?: Opcodes.getDefault())
        picked.forEach(pool::internClass)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }

    /** Имя для нового dex: classes.dex, classes2.dex, … → следующий свободный. */
    fun nextDexName(existing: Collection<String>): String {
        var n = 2
        while ("classes$n.dex" in existing) n++
        return if ("classes.dex" !in existing) "classes.dex" else "classes$n.dex"
    }

    private fun findLoadLibrarySite(dexes: Map<String, DexBackedDexFile>, gameLib: String): Site? {
        val candidates = ArrayList<Site>()
        for ((name, dex) in dexes) for (cls in dex.classes) for (m in cls.methods) {
            val impl = m.implementation ?: continue
            val strings = HashMap<Int, String>() // регистр → последняя загруженная строка
            impl.instructions.forEachIndexed { i, ins ->
                when (ins.opcode) {
                    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> {
                        val reg = (ins as OneRegisterInstruction).registerA
                        strings[reg] = ((ins as ReferenceInstruction).reference as StringReference).string
                    }
                    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> {
                        val ref = (ins as ReferenceInstruction).reference as MethodReference
                        val isLoad = ref.definingClass == "Ljava/lang/System;" && ref.name == "loadLibrary"
                        val arg = when (ins) {
                            is Instruction35c -> ins.registerC
                            is Instruction3rc -> ins.startRegister
                            else -> -1
                        }
                        if (isLoad && strings[arg] == gameLib) {
                            candidates += Site(name, cls.type, m, i + 1, HookKind.AFTER_LOAD_LIBRARY)
                        }
                    }
                    else -> if (ins is OneRegisterInstruction && ins.opcode.setsRegister()) strings.remove(ins.registerA)
                }
            }
        }
        // Предпочитаем статический инициализатор — так AML грузится как можно раньше.
        return candidates.minByOrNull { if (it.method.name == "<clinit>") 0 else 1 }
    }

    private fun findOnCreateSite(dexes: Map<String, DexBackedDexFile>, mainActivity: String?): Site? {
        if (mainActivity == null) return null
        val byType = dexes.flatMap { (n, d) -> d.classes.map { it.type to (n to it) } }.toMap()
        var type: String? = "L" + mainActivity.replace('.', '/') + ";"
        while (type != null) {
            val (dexName, cls) = byType[type] ?: return null
            val onCreate = cls.methods.firstOrNull {
                it.name == "onCreate" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;") && it.implementation != null
            }
            if (onCreate != null) return Site(dexName, cls.type, onCreate, 0, HookKind.MAIN_ACTIVITY_ON_CREATE)
            type = cls.superclass
        }
        return null
    }

    private fun rewriteClass(cls: ClassDef, site: Site): ClassDef {
        val methods = cls.methods.map { m ->
            if (m.name != site.method.name || m.parameterTypes.map(CharSequence::toString) != site.method.parameterTypes.map(CharSequence::toString) ||
                m.returnType != site.method.returnType
            ) {
                m
            } else {
                val impl = MutableMethodImplementation(m.implementation!!)
                impl.addInstruction(site.insertAt, BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, HOOK_REF))
                ImmutableMethod(m.definingClass, m.name, m.parameters, m.returnType, m.accessFlags, m.annotations, m.hiddenApiRestrictions, impl)
            }
        }
        return ImmutableClassDef(
            cls.type, cls.accessFlags, cls.superclass, cls.interfaces, cls.sourceFile, cls.annotations,
            cls.fields, methods,
        )
    }
}

class PatchException(message: String, cause: Throwable? = null) : Exception(message, cause)

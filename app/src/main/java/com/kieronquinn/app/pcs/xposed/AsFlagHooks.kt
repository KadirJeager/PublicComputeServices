package com.kieronquinn.app.pcs.xposed

import com.kieronquinn.app.pcs.asi.AsFlagOverrides
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 *  Overrides Android System Intelligence's own feature flags by name ([AsFlagOverrides]).
 *
 *  ASI declares each flag as a value object (`BooleanFlag{name=…}`, `StringListFlag{name=…}`,
 *  `StringFlag{name=…}`, the last used for comma separated lists)
 *  and reads every one through one reader interface, which resolves the device_config value and
 *  falls back to the default. The reader's implementations are hooked; a flag with an override
 *  gets it, every other flag keeps the value ASI resolved. Unlike writing device_config, nothing
 *  persists outside ASI's process and a server sync cannot undo it.
 *
 *  The classes are found with DexKit from their `toString` text; the reader methods from their
 *  signatures (flag, optional default) to value.
 */
internal object AsFlagHooks {

    private const val TAG = "AsHooks"
    private const val BOOLEAN_FLAG = "BooleanFlag{name="
    private const val STRING_LIST_FLAG = "StringListFlag{name="
    private const val STRING_FLAG = "StringFlag{name="

    fun install(dexKit: DexKitBridge, classLoader: ClassLoader, overrides: AsFlagOverrides) {
        if (overrides.isEmpty()) return
        try {
            val booleanFlag = flagClass(dexKit, classLoader, BOOLEAN_FLAG)
            val listFlag = flagClass(dexKit, classLoader, STRING_LIST_FLAG)
            val booleanName = nameField(booleanFlag)
            val listName = nameField(listFlag)
            // The list flags' value type (Guava ImmutableList): the type of their default value.
            val listType = listFlag.declaredFields.first {
                !Modifier.isStatic(it.modifiers) && it.type != String::class.java
            }.type
            val booleanReaders = readers(dexKit, classLoader, booleanFlag, java.lang.Boolean::class.java)
            val listReaders = readers(dexKit, classLoader, listFlag, listType)
            check(booleanReaders.isNotEmpty() && listReaders.isNotEmpty()) { "No flag readers" }
            // ImmutableList.copyOf(Collection)
            val copyOf = listType.declaredMethods.first {
                Modifier.isStatic(it.modifiers) && it.returnType == listType &&
                    it.parameterTypes.contentEquals(arrayOf(Collection::class.java))
            }.apply { isAccessible = true }
            booleanReaders.forEach { reader ->
                XposedBridge.hookMethod(reader, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = booleanName.get(param.args[0]) as? String ?: return
                        overrides.boolean(name)?.let { param.result = it }
                    }
                })
            }
            listReaders.forEach { reader ->
                XposedBridge.hookMethod(reader, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = listName.get(param.args[0]) as? String ?: return
                        val current = (param.result as? List<*>)?.filterIsInstance<String>() ?: return
                        val changed = overrides.list(name, current) ?: return
                        param.result = copyOf.invoke(null, changed)
                    }
                })
            }
            val stringReaders = stringReaders(dexKit, classLoader, overrides)
            log("ASI flag overrides installed (${overrides.size}) on " +
                "${booleanReaders.size + listReaders.size + stringReaders} reader method(s)")
        } catch (error: Throwable) {
            log("ASI flag overrides unavailable: $error")
        }
    }

    /**
     *  Hooks the string flag readers, for overrides of comma separated lists. Returns the number
     *  of readers hooked; a failure here leaves the other flags' overrides in place.
     */
    private fun stringReaders(dexKit: DexKitBridge, classLoader: ClassLoader, overrides: AsFlagOverrides): Int {
        return try {
            val stringFlag = flagClass(dexKit, classLoader, STRING_FLAG)
            val stringName = nameField(stringFlag)
            val readers = readers(dexKit, classLoader, stringFlag, String::class.java)
            readers.forEach { reader ->
                XposedBridge.hookMethod(reader, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = stringName.get(param.args[0]) as? String ?: return
                        val current = param.result as? String ?: return
                        overrides.commaList(name, current)?.let { param.result = it }
                    }
                })
            }
            readers.size
        } catch (error: Throwable) {
            log("ASI string flag overrides unavailable: $error")
            0
        }
    }

    private fun flagClass(dexKit: DexKitBridge, classLoader: ClassLoader, marker: String): Class<*> =
        dexKit.findClass { matcher { usingStrings(marker) } }.single().getInstance(classLoader)

    /** The flag's name: its first String field, as `toString` prints it. */
    private fun nameField(type: Class<*>): Field = type.declaredFields.first {
        !Modifier.isStatic(it.modifiers) && it.type == String::class.java
    }.apply { isAccessible = true }

    /** Concrete methods taking (flag) or (flag, default [value]) and returning a [value]. */
    private fun readers(dexKit: DexKitBridge, classLoader: ClassLoader, flag: Class<*>, value: Class<*>): List<Method> =
        dexKit.findMethod { matcher { paramTypes(flag.name) } }
            .plus(dexKit.findMethod { matcher { paramTypes(flag.name, value.name) } })
            .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
            .filter { !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .filter { it.returnType == value }
            .distinct()

    private fun log(message: String) = XposedBridge.log("$TAG: $message")

}

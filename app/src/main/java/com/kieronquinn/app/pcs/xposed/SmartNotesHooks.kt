package com.kieronquinn.app.pcs.xposed

import android.content.res.Resources
import com.kieronquinn.app.pcs.callnotes.SmartNotesFeature
import com.kieronquinn.app.pcs.repositories.DeviceConfigPropertiesRepository.Companion.AICORE_CALL_NOTES_CALL_LANGUAGE
import com.kieronquinn.app.pcs.utils.extensions.SystemProperties_getBoolean
import com.kieronquinn.app.pcs.utils.extensions.loadDexKit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  Has AICore write Call Notes summaries in the call's language ([SmartNotesFeature]).
 *
 *  AICore reads each feature's definition from its model file group's metadata, parsing a
 *  `FeatureExperiment` from a `ByteString` with protobuf-lite's generic parser. The parse call is
 *  found from AICore's log line for a failed parse, and the bytes are rewritten before parsing
 *  when they are a `FeatureExperiment` holding feature 208. Nothing else AICore parses is changed.
 */
object SmartNotesHooks : XposedHooks {

    override val tag = "AiCoreHooks"

    private const val PARSE_FAILED_LOG =
        "Failed to parse FeatureExperiment proto from Any value for file group %s"

    private val logged = AtomicBoolean(false)

    override fun hook(loadPackageParam: LoadPackageParam) {
        if (!SystemProperties_getBoolean(AICORE_CALL_NOTES_CALL_LANGUAGE, false)) return
        try {
            install(loadPackageParam)
        } catch (error: Throwable) {
            log("Call Notes summary language unavailable: $error")
        }
    }

    private fun install(loadPackageParam: LoadPackageParam) {
        val classLoader = loadPackageParam.classLoader
        val caller = loadDexKit(loadPackageParam.appInfo.sourceDir)
            .findMethod { matcher { usingStrings(PARSE_FAILED_LOG) } }
            .single()
        // GeneratedMessageLite.parseFrom(T defaultInstance, ByteString, ExtensionRegistryLite): T
        val parse = caller.invokes.filter { it.isMethod }
            .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
            .filter { Modifier.isStatic(it.modifiers) }
            .single {
                it.parameterCount == 3 && it.returnType == it.parameterTypes[0] &&
                    it.parameterTypes[1] != ByteArray::class.java && !it.parameterTypes[1].isPrimitive
            }
        // The message types whose default instance the caller reads (FeatureExperiment among them).
        val messageTypes = caller.usingFields
            .map { it.field }
            .filter { it.declaredClassName == it.typeName }
            .mapNotNull { runCatching { it.getFieldInstance(classLoader).type }.getOrNull() }
            .filter { parse.parameterTypes[0].isAssignableFrom(it) }
            .toSet()
        check(messageTypes.isNotEmpty()) { "No message type read by ${caller.name}" }
        val byteString = parse.parameterTypes[1]
        val toBytes = toByteArray(byteString)
        val fromBytes = copyFrom(byteString)
        val template = SmartNotesFeature.template(
            SmartNotesFeature.LanguageHints.forLocale(Resources.getSystem().configuration.locales.get(0)) { path ->
                SmartNotesHooks::class.java.classLoader?.getResourceAsStream("assets/$path")
            }
        )
        XposedBridge.hookMethod(parse, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val message = param.args[0] ?: return
                val data = param.args[1] ?: return
                if (message.javaClass !in messageTypes) return
                try {
                    val bytes = toBytes.invoke(data) as ByteArray
                    val rewritten = SmartNotesFeature.rewrite(bytes, template) ?: return
                    param.args[1] = fromBytes.invoke(null, rewritten)
                    if (logged.compareAndSet(false, true)) {
                        log("Call Notes summaries use the call's language (feature ${SmartNotesFeature.FEATURE_ID})")
                    }
                } catch (error: Throwable) {
                    log("Call Notes summary definition left as it is: $error")
                }
            }
        })
        log("Call Notes summary language hook installed on ${parse.declaringClass.name}.${parse.name}")
    }

    /** ByteString.toByteArray(): the one byte[]-returning instance method without parameters. */
    private fun toByteArray(type: Class<*>): Method = generateSequence(type) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredMethods.asSequence() }
        .filter {
            !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                it.parameterCount == 0 && it.returnType == ByteArray::class.java
        }
        .toList()
        .distinctBy { it.name }
        .single()
        .apply { isAccessible = true }

    /** ByteString.copyFrom(byte[]): the one public static byte[] to ByteString method. */
    private fun copyFrom(type: Class<*>): Method = type.declaredMethods.single {
        Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) &&
            it.parameterTypes.contentEquals(arrayOf(ByteArray::class.java)) &&
            type.isAssignableFrom(it.returnType)
    }.apply { isAccessible = true }

}

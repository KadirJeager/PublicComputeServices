package com.kieronquinn.app.pcs.callnotes

import com.google.gson.Gson
import com.kieronquinn.app.pcs.voicepack.ProtoWire
import com.kieronquinn.app.pcs.voicepack.ProtoWire.Field
import com.kieronquinn.app.pcs.voicepack.ProtoWire.LENGTH_DELIMITED
import com.kieronquinn.app.pcs.voicepack.ProtoWire.VARINT
import java.io.InputStream
import java.util.Locale

/**
 *  Makes AICore's Call Notes summary feature (208, "Dialer Smart Notes") write in the call's
 *  language.
 *
 *  Google defines the feature with a LoRA adapter trained on English only and a one-line prompt
 *  ("Summarize the following conversation: %1$s"). A transcript in another language passed through
 *  that adapter is misread, repeated, cut off and summarised in English; translating the summary
 *  afterwards does not fix that. The same base model without the adapter writes in the language
 *  asked for, as Recorder's summaries in other languages show.
 *
 *  AICore reads the feature's definition as a `FeatureExperiment` protobuf. [rewrite] removes the
 *  adapter (model.10.3) of feature [FEATURE_ID] and replaces its prompt template (config.1); every
 *  other field (allowed packages and signatures, versions, safety settings) is kept. Phone's input
 *  and output formats are unchanged: the input is "**Outgoing Call**\nYou: …\nOther: …", and the
 *  output one sentence followed by bullet points starting with "*".
 */
object SmartNotesFeature {

    const val FEATURE_ID = 208L

    /**
     *  Optional wording for one language that the model gets wrong without it, shipped as
     *  `assets/callnotes/<language>.json`.
     *
     *  @param secondPerson how to address the phone's owner, with examples.
     *  @param otherPerson the word for the other person on the call.
     */
    data class LanguageHints(val language: String, val secondPerson: String?, val otherPerson: String?) {

        private class Data(val version: Int?, val locale: String?, val second_person: String?, val other_person: String?)

        companion object {
            const val ASSET_DIRECTORY = "callnotes"

            /**
             *  Hints for [locale]: its language's English name, with the wording from
             *  `callnotes/<tag or language>.json` if PCS ships one (read with [open], a path to its
             *  stream or null). English needs none.
             */
            fun forLocale(locale: Locale, open: (String) -> InputStream?): LanguageHints? {
                if (locale.language.isEmpty() || locale.language == "en") return null
                val name = locale.getDisplayLanguage(Locale.ENGLISH)
                val data = listOf("${locale.toLanguageTag()}.json", "${locale.language}.json")
                    .firstNotNullOfOrNull { open("$ASSET_DIRECTORY/$it") }
                    ?.reader()?.use { Gson().fromJson(it, Data::class.java) }
                    ?.takeIf {
                        it.version == 1 && Locale.forLanguageTag(it.locale.orEmpty()).language == locale.language
                    }
                return LanguageHints(
                    name,
                    data?.second_person?.takeIf { it.isNotBlank() },
                    data?.other_person?.takeIf { it.isNotBlank() }
                )
            }
        }
    }

    /** The prompt, adapted from Recorder's summary prompt for the base model. */
    fun template(hints: LanguageHints?): String {
        val language = hints?.language
        val secondPerson = hints?.secondPerson?.let { " (in $language: $it)" }.orEmpty()
        val otherPerson = hints?.otherPerson?.let { " (in $language: \"$it\")" }.orEmpty()
        val fluency = language?.let {
            " If the conversation is in $it, write natural, fluent and grammatical $it."
        }.orEmpty()
        return "<ctrl99>system\n" +
            "You are an expert in phone call summarization. The transcript starts with a line saying" +
            " whether the call was incoming or outgoing. Each following line starts with \"You:\"" +
            " (the owner of this phone) or \"Other:\" (the other person on the call). When" +
            " summarizing, adhere to these guidelines:\n\n" +
            "- Language: Write the whole summary in the same language as the conversation.$fluency\n" +
            "- Perspective: Address the owner of this phone directly and consistently in the polite" +
            " second person$secondPerson. Refer to the other person as \"the other person\"$otherPerson;" +
            " never use the first person.\n" +
            "- Prioritize useful details: purpose of the call, decisions, plans, requests, dates," +
            " times, places, amounts and names.\n" +
            "- Maintain accuracy: Do not add information that is not explicitly stated in the" +
            " conversation. Speech recognition may contain errors; do not repeat words that make" +
            " no sense in context.\n" +
            "- Respect privacy: Do not guess the speakers' genders or other personal attributes.\n" +
            "- No repetition: Every bullet point must state a different point.\n" +
            "- Plain text format: No headers, bold text, markdown or other additional" +
            " information.<ctrl100>\n" +
            "<ctrl99>user\n%1\$s\n\n" +
            "First write one short sentence, addressed to the owner of this phone, that states why" +
            " the call was made (for example \"You called to ask …\" or \"You were called about" +
            " …\", in the language of the conversation). Then summarize the call in at most 5" +
            " bullet points, using no more than 2 sentences per bullet; use fewer bullet points" +
            " for a short call. Begin each bullet point with \"* \" and do not use the \"*\"" +
            " character anywhere else.<ctrl100>\n" +
            "<ctrl99>model\n"
    }

    /**
     *  [experiment] (FeatureExperiment: 1 control Feature, 2 treatment Features) with feature
     *  [FEATURE_ID] using [template] and no adapter, or null if it has no such feature, is already
     *  rewritten, or is not in the expected shape. Feature: 1 id, 3 model (10: 2 version
     *  directory, 3 adapter file, 4 base model), 8 inference config (1 prompt template).
     */
    fun rewrite(experiment: ByteArray, template: String): ByteArray? {
        val fields = ProtoWire.parseOrNull(experiment) ?: return null
        var changed = false
        val rewritten = fields.map { field ->
            if ((field.number == 1 || field.number == 2) && field.wireType == LENGTH_DELIMITED) {
                rewriteFeature(field.bytes, template)?.let { changed = true; field.withBytes(it) } ?: field
            } else field
        }
        return if (changed) ProtoWire.encode(rewritten) else null
    }

    private fun rewriteFeature(feature: ByteArray, template: String): ByteArray? {
        val fields = ProtoWire.parseOrNull(feature) ?: return null
        val id = fields.firstOrNull { it.number == 1 && it.wireType == VARINT }?.value as? Long
        if (id != FEATURE_ID) return null
        val model = fields.firstOrNull { it.number == 3 && it.wireType == LENGTH_DELIMITED } ?: return null
        val config = fields.firstOrNull { it.number == 8 && it.wireType == LENGTH_DELIMITED } ?: return null
        // Leave a shape we do not know alone rather than half-change it.
        val newModel = withoutAdapter(model.bytes) ?: return null
        val newConfig = withTemplate(config.bytes, template) ?: return null
        if (newModel.contentEquals(model.bytes) && newConfig.contentEquals(config.bytes)) return null
        return ProtoWire.encode(fields.map {
            when (it) {
                model -> it.withBytes(newModel)
                config -> it.withBytes(newConfig)
                else -> it
            }
        })
    }

    /** The model message without adapter files; null if a model file list has no base model. */
    private fun withoutAdapter(model: ByteArray): ByteArray? {
        val fields = ProtoWire.parseOrNull(model) ?: return null
        val rewritten = fields.map { field ->
            if (field.number != 10 || field.wireType != LENGTH_DELIMITED) return@map field
            val files = ProtoWire.parseOrNull(field.bytes) ?: return null
            if (files.none { it.number == 4 && it.wireType == LENGTH_DELIMITED }) return null
            field.withBytes(ProtoWire.encode(files.filterNot { it.number == 3 }))
        }
        return ProtoWire.encode(rewritten)
    }

    /** The inference config with [template]; null if it has no prompt template. */
    private fun withTemplate(config: ByteArray, template: String): ByteArray? {
        val fields = ProtoWire.parseOrNull(config) ?: return null
        val index = fields.indexOfFirst { it.number == 1 && it.wireType == LENGTH_DELIMITED }
        if (index < 0) return null
        return ProtoWire.encode(fields.toMutableList().apply {
            this[index] = this[index].withBytes(template.toByteArray(Charsets.UTF_8))
        })
    }

}

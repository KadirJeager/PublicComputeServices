package com.kieronquinn.app.pcs.callnotes

import com.kieronquinn.app.pcs.voicepack.ProtoWire
import com.kieronquinn.app.pcs.voicepack.ProtoWire.Field
import com.kieronquinn.app.pcs.voicepack.ProtoWire.LENGTH_DELIMITED
import com.kieronquinn.app.pcs.voicepack.ProtoWire.VARINT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class SmartNotesFeatureTest {

    private fun bytes(text: String) = text.toByteArray()
    private fun message(vararg fields: Field) = ProtoWire.encode(fields.toList())
    private fun child(number: Int, vararg fields: Field) = Field(number, LENGTH_DELIMITED, message(*fields))
    private fun string(number: Int, text: String) = Field(number, LENGTH_DELIMITED, bytes(text))

    /** Feature: 1 id, 2 name, 3 model (10: 2 version dir, 3 adapter, 4 base model), 8 config (1 template, 2 other). */
    private fun feature(id: Long, adapter: Boolean = true) = message(
        Field(1, VARINT, id),
        string(2, "Dialer Smart Notes V5"),
        child(3, child(10, *listOfNotNull(
            string(2, "v5"),
            if (adapter) string(3, "sn_v4_craft_lora.binarypb") else null,
            string(4, "nano-v4-full")
        ).toTypedArray()), Field(11, VARINT, 7L)),
        child(8, string(1, "Summarize the following conversation: %1\$s"), Field(2, VARINT, 1L)),
        string(9, "com.google.android.dialer")
    )

    private fun experiment(vararg features: ByteArray) =
        ProtoWire.encode(features.mapIndexed { index, it -> Field(if (index == 0) 1 else 2, LENGTH_DELIMITED, it) })

    private val template = SmartNotesFeature.template(null)

    private fun fields(bytes: ByteArray) = ProtoWire.parse(bytes)
    private fun sub(bytes: ByteArray, number: Int) = fields(bytes).first { it.number == number }.bytes

    @Test
    fun `summary feature loses its adapter and gets the prompt`() {
        val rewritten = SmartNotesFeature.rewrite(experiment(feature(208)), template)!!
        val feature = sub(rewritten, 1)
        val files = fields(sub(sub(feature, 3), 10))
        assertEquals(listOf(2, 4), files.map { it.number })
        assertEquals("nano-v4-full", String(files.last().bytes))
        val config = fields(sub(feature, 8))
        assertEquals(template, String(config.first { it.number == 1 }.bytes))
        assertEquals(1L, config.first { it.number == 2 }.value)
        // Everything else is kept.
        assertEquals("com.google.android.dialer", String(sub(feature, 9)))
        assertEquals(7L, fields(sub(feature, 3)).first { it.number == 11 }.value)
    }

    @Test
    fun `other features are not changed`() {
        val other = feature(217)
        val rewritten = SmartNotesFeature.rewrite(experiment(other, feature(208)), template)!!
        assertArrayEquals(other, sub(rewritten, 1))
        assertTrue(String(sub(sub(fields(rewritten).first { it.number == 2 }.bytes, 8), 1)).contains("<ctrl99>system"))
        assertNull(SmartNotesFeature.rewrite(experiment(other), template))
    }

    @Test
    fun `already rewritten feature is left alone`() {
        val once = SmartNotesFeature.rewrite(experiment(feature(208)), template)!!
        assertNull(SmartNotesFeature.rewrite(once, template))
    }

    @Test
    fun `unknown shapes are left alone`() {
        val noBaseModel = message(
            Field(1, VARINT, 208L),
            child(3, child(10, string(3, "adapter"))),
            child(8, string(1, "Summarize"))
        )
        assertNull(SmartNotesFeature.rewrite(experiment(noBaseModel), template))
        val noTemplate = message(Field(1, VARINT, 208L), child(3, child(10, string(4, "base"))), child(8, Field(2, VARINT, 1L)))
        assertNull(SmartNotesFeature.rewrite(experiment(noTemplate), template))
        assertNull(SmartNotesFeature.rewrite(byteArrayOf(0x0a, 0x7f), template))
    }

    @Test
    fun `prompt keeps Phone's placeholder and output format`() {
        assertEquals(1, Regex("%1\\\$s").findAll(template).count())
        assertTrue(template.contains("Begin each bullet point with \"* \""))
        assertTrue(template.contains("same language as the conversation"))
    }

    private fun openAsset(path: String) = File("src/main/assets", path).takeIf { it.isFile }?.inputStream()

    @Test
    fun `Turkish hints come from the shipped file`() {
        val hints = SmartNotesFeature.LanguageHints.forLocale(Locale.forLanguageTag("tr-TR"), ::openAsset)!!
        assertEquals("Turkish", hints.language)
        assertEquals("karşı taraf", hints.otherPerson)
        val prompt = SmartNotesFeature.template(hints)
        assertTrue(prompt.contains("write natural, fluent and grammatical Turkish"))
        assertTrue(prompt.contains("(in Turkish: \"karşı taraf\")"))
    }

    @Test
    fun `other languages get their name without wording, English nothing`() {
        val hints = SmartNotesFeature.LanguageHints.forLocale(Locale.GERMANY, ::openAsset)!!
        assertEquals(SmartNotesFeature.LanguageHints("German", null, null), hints)
        assertNull(SmartNotesFeature.LanguageHints.forLocale(Locale.US, ::openAsset))
    }

}

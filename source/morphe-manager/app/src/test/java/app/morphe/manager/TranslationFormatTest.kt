/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.fail

/**
 * Translations reach dev from Crowdin unreviewed, and a wrong placeholder only shows as a crash
 * on a device in that language.
 */
class TranslationFormatTest {
    private val res = File("src/main/res")

    @Test
    fun `translations format with the arguments of their source string`() {
        val failures = RESOURCE_FILES.flatMap { fileName ->
            val sources = readEntries(File(res, "values/$fileName")).groupBy { it.name }
            val translations = res.listFiles { dir -> dir.name.startsWith("values-") }.orEmpty()
                .map { File(it, fileName) }
                .filter(File::exists)
                .sorted()
            translations.flatMap { file ->
                readEntries(file).mapNotNull { entry ->
                    val source = sources[entry.name] ?: return@mapNotNull null
                    if (source.any { !it.formatted }) return@mapNotNull null
                    // Plural forms take the same arguments, so any form tells what the code passes
                    val arguments = source.firstNotNullOfOrNull { sampleArguments(it.text) }
                        ?: return@mapNotNull null
                    val error = runCatching { String.format(Locale.ROOT, entry.text, *arguments) }
                        .exceptionOrNull() ?: return@mapNotNull null
                    val sourceText = (source.find { it.quantity == entry.quantity } ?: source.last()).text
                    val folder = file.parentFile?.name.orEmpty()
                    """
                    |${languageName(folder)}  $folder/${file.name}  ${entry.label}
                    |  source:      $sourceText
                    |  translation: ${entry.text}
                    |  error:       ${error::class.simpleName}: ${error.message}
                    """.trimMargin()
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} translation(s) would crash when formatted. Fix them in Crowdin:\n\n" +
                        failures.joinToString("\n\n")
            )
        }
    }

    /** The language a resource folder holds, as Crowdin lists it, with its tag. */
    private fun languageName(folder: String): String {
        val parts = folder.removePrefix("values-").split("-r")
        val tag = parts.joinToString("-")
        val name = CROWDIN_NAMES[tag]
            ?: Locale.Builder().setLanguage(parts[0]).build().getDisplayLanguage(Locale.ENGLISH)
        return "$name ($tag)"
    }

    private class Entry(val name: String, val quantity: String?, val text: String, val formatted: Boolean) {
        val label get() = if (quantity == null) name else "$name ($quantity)"
    }

    private fun readEntries(file: File): List<Entry> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val strings = root.elements("string").map {
            Entry(it.getAttribute("name"), null, androidText(it.textContent), it.isFormatted)
        }
        val plurals = root.elements("plurals").flatMap { plural ->
            plural.elements("item").map {
                Entry(plural.getAttribute("name"), it.getAttribute("quantity"), androidText(it.textContent), plural.isFormatted)
            }
        }
        return strings + plurals
    }

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private val Element.isFormatted get() = getAttribute("formatted") != "false"

    /** The text Android hands to the formatter: escapes resolved and quoting removed. */
    private fun androidText(raw: String) = buildString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i++]
            when {
                c == '"' -> Unit
                c != '\\' || i == raw.length -> append(c)
                raw[i] == 'u' && i + 5 <= raw.length -> {
                    append(raw.substring(i + 1, i + 5).toInt(16).toChar())
                    i += 5
                }
                else -> append(
                    when (val escaped = raw[i++]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> escaped
                    }
                )
            }
        }
    }

    /** One argument per placeholder in [text], of the type it asks for, or null without any. */
    private fun sampleArguments(text: String): Array<Any>? {
        val types = sortedMapOf<Int, Char>()
        var next = 1
        for (match in PLACEHOLDER.findAll(text)) {
            val conversion = match.groupValues[2].last()
            if (conversion == '%' || conversion == 'n') continue
            types.putIfAbsent(match.groupValues[1].toIntOrNull() ?: next++, conversion)
        }
        if (types.isEmpty()) return null
        return Array(types.lastKey()) { index ->
            when (types[index + 1]?.lowercaseChar()) {
                'd', 'o', 'x' -> 1
                'e', 'f', 'g', 'a' -> 1.0
                'c' -> 'x'
                'b' -> true
                else -> "x"
            }
        }
    }

    private companion object {
        val RESOURCE_FILES = listOf("strings.xml", "plurals.xml")

        /** Crowdin's names where they differ from the English name Java gives the language. */
        val CROWDIN_NAMES = mapOf(
            "bn-BD" to "Bengali",
            "ckb-IR" to "Sorani (Kurdish)",
            "kmr-TR" to "Kurmanji (Kurdish)",
            "pt-BR" to "Portuguese, Brazilian",
            "sr-CS" to "Serbian (Latin)",
            "sr-SP" to "Serbian (Cyrillic)",
            "zh-CN" to "Chinese Simplified",
            "zh-TW" to "Chinese Traditional"
        )

        /** A java.util.Formatter placeholder: optional index, flags, width, precision, conversion. */
        val PLACEHOLDER = Regex("""%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([tT]?[a-zA-Z%])""")
    }
}

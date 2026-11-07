package com.huangder.lumibooks.util

import android.content.Context
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.util.parser.BookParserFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.zip.ZipFile

internal data class ImportBookMetadata(
    val title: String = "",
    val author: String = "",
    val isbns: List<String> = emptyList()
)

/** Returns a checksum-validated ISBN-13, also accepting ISBN-10 and common separators. */
internal fun normalizeImportIsbn(value: String): String? {
    val raw = value.uppercase().replace(Regex("[^0-9X]"), "")
    if (raw.length == 13 && raw.all(Char::isDigit) && (raw.startsWith("978") || raw.startsWith("979"))) {
        val sum = raw.mapIndexed { index, c -> (c - '0') * if (index % 2 == 0) 1 else 3 }.sum()
        return raw.takeIf { sum % 10 == 0 }
    }
    if (raw.length != 10 || !raw.take(9).all(Char::isDigit) || !(raw.last().isDigit() || raw.last() == 'X')) return null
    val sum = raw.mapIndexed { index, c -> (if (c == 'X') 10 else c - '0') * (10 - index) }.sum()
    if (sum % 11 != 0) return null
    val prefix = "978" + raw.take(9)
    val check = (10 - prefix.mapIndexed { index, c -> (c - '0') * if (index % 2 == 0) 1 else 3 }.sum() % 10) % 10
    return prefix + check
}

internal fun findImportIsbns(text: String): List<String> {
    // Require an ISBN label for ISBN-10; arbitrary ten-digit numbers are often phone numbers.
    val labelled = Regex("(?i)ISBN(?:[- ]?(?:10|13))?\\s*[:：]?\\s*((?:[0-9X][ \t\u00a0‐‑–-]*){9,12}[0-9X])(?![0-9X])")
    val bare13 = Regex("(?<![0-9])97[89](?:[ \t\u00a0‐‑–-]*[0-9]){10}(?![0-9])")
    return (labelled.findAll(text).map { it.groupValues[1] } + bare13.findAll(text).map { it.value })
        .mapNotNull(::normalizeImportIsbn).distinct().toList()
}

/** Bounded copyright-page scan. Image-only books deliberately fall back to manual review. */
internal fun readImportBookMetadata(context: Context, location: String, fileName: String): ImportBookMetadata {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension == "epub") return BookFileAccess.openSeekable(context, location).use { source ->
        ZipFile(source.path).use { zip ->
            fun read(path: String): String = zip.getEntry(path)?.let { entry ->
                zip.getInputStream(entry).use { readImportPrefix(it).toString(Charsets.UTF_8) }
            }.orEmpty()
            val container = Jsoup.parse(read("META-INF/container.xml"), "", Parser.xmlParser())
            val opfPath = container.selectFirst("rootfile")?.attr("full-path").orEmpty()
            require(opfPath.isNotBlank()) { "Missing EPUB package" }
            val opf = Jsoup.parse(read(opfPath), "", Parser.xmlParser())
            val metadata = opf.getAllElements().firstOrNull { it.tagName().substringAfter(':') == "metadata" }
            fun field(tag: String) = metadata?.getAllElements()?.firstOrNull { it.tagName().substringAfter(':') == tag }?.text().orEmpty()
            val identifiers = metadata?.getAllElements()?.filter { it.tagName().substringAfter(':') == "identifier" }
                ?.mapNotNull { element ->
                    val value = element.text().removePrefix("urn:isbn:").removePrefix("ISBN:")
                    normalizeImportIsbn(value)
                }?.distinct().orEmpty()
            val folder = opfPath.substringBeforeLast('/', "")
            val items = opf.getElementsByTag("item").associateBy { it.attr("id") }
            val text = opf.getElementsByTag("itemref").take(6).joinToString("\n") { ref ->
                val href = items[ref.attr("idref")]?.attr("href").orEmpty().substringBefore('#')
                val path = java.net.URI(null, null, (if (folder.isEmpty()) "" else "$folder/") + href, null).normalize().path
                Jsoup.parse(read(path)).text()
            }
            ImportBookMetadata(field("title"), field("creator"), identifiers.ifEmpty { findImportIsbns(text) })
        }
    }
    if (extension == "pdf") return BookFileAccess.openSeekable(context, location).use { source ->
        PDDocument.load(java.io.File(source.path), MemoryUsageSetting.setupMixed(16L * 1024 * 1024).apply { tempDir = context.cacheDir }).use { pdf ->
            val stripper = PDFTextStripper().apply { startPage = 1; endPage = minOf(pdf.numberOfPages, 8) }
            val front = if (pdf.numberOfPages > 0) stripper.getText(pdf) else ""
            val back = if (pdf.numberOfPages > 8) {
                stripper.startPage = maxOf(9, pdf.numberOfPages - 1)
                stripper.endPage = pdf.numberOfPages
                stripper.getText(pdf)
            } else ""
            ImportBookMetadata(pdf.documentInformation.title.orEmpty(), pdf.documentInformation.author.orEmpty(), findImportIsbns(front + "\n" + back))
        }
    }
    if (extension in setOf("txt", "md", "markdown")) {
        val bytes = BookFileAccess.openInputStream(context, location).use { readImportPrefix(it) }
        val utf8 = bytes.toString(Charsets.UTF_8)
        val text = if ('\uFFFD' in utf8) bytes.toString(charset("GB18030")) else utf8
        return ImportBookMetadata(isbns = findImportIsbns(text + "\n" + fileName))
    }
    if (extension in setOf("mobi", "azw", "azw3")) {
        val parser = BookParserFactory.createParser(BookFormat.valueOf(extension.uppercase()), context)
        try {
            val book = parser.parse(location)
            val text = (0 until minOf(parser.getChapterCount(), 6)).joinToString("\n") {
                parser.getChapterContent(it).take(100_000).toString()
            }
            return ImportBookMetadata(book.title, book.author, findImportIsbns(text))
        } finally { parser.close() }
    }
    return ImportBookMetadata(isbns = findImportIsbns(fileName))
}

private fun readImportPrefix(input: java.io.InputStream): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var remaining = 512 * 1024
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}

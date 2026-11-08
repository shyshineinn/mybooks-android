package com.huangder.lumibooks.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImportBookMetadataTest {
    @Test fun isbnChecksumsAndTenDigitConversion() {
        assertEquals("9780306406157", normalizeImportIsbn("0-306-40615-2"))
        assertEquals("9780306406157", normalizeImportIsbn("978-0-306-40615-7"))
        assertNull(normalizeImportIsbn("9780306406158"))
        assertNull(normalizeImportIsbn("1234567890"))
        assertEquals("9780804429573", normalizeImportIsbn("080442957X"))
    }
    @Test fun copyrightTextRejectsPhoneNumbersAndDeduplicatesEquivalentIsbns() {
        assertEquals(listOf("9780306406157"), findImportIsbns("ISBN-10: 0-306-40615-2\nISBN 978–0–306–40615–7"))
        assertEquals(emptyList<String>(), findImportIsbns("电话 0306406152，编号 9780306406158"))
        assertEquals(listOf("9780306406157"), findImportIsbns("Book_9780306406157.epub"))
    }
    @Test fun epubMetadataAndCopyrightPageAreReadWithoutParsingTheWholeBook() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("isbn-", ".epub", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
                entry("OEBPS/content.opf", """<package><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Book</dc:title><dc:creator>Author</dc:creator><dc:identifier>uuid:random</dc:identifier></metadata><manifest><item id="copyright" href="text/copyright.xhtml"/></manifest><spine><itemref idref="copyright"/></spine></package>""")
                entry("OEBPS/text/copyright.xhtml", "<html><body>ISBN: 978-0-306-40615-7</body></html>")
            }
            val metadata = readImportBookMetadata(context, file.path, "filename.epub")
            assertEquals("Book", metadata.title)
            assertEquals("Author", metadata.author)
            assertEquals(listOf("9780306406157"), metadata.isbns)
        } finally { file.delete() }
    }
    @Test fun pdfCopyrightTextProvidesIsbnAndDocumentMetadata() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("isbn-", ".pdf", context.cacheDir)
        try {
            com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
                val page = com.tom_roush.pdfbox.pdmodel.PDPage()
                document.addPage(page)
                document.documentInformation.title = "Book"
                document.documentInformation.author = "Author"
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(30f, 700f)
                    stream.showText("ISBN: 978-0-306-40615-7")
                    stream.endText()
                }
                document.save(file)
            }
            val metadata = readImportBookMetadata(context, file.path, "file.pdf")
            assertEquals("Book", metadata.title)
            assertEquals("Author", metadata.author)
            assertEquals(listOf("9780306406157"), metadata.isbns)
        } finally { file.delete() }
    }

}

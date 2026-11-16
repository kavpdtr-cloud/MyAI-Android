package ru.myai.client

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

 data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val base64: String? = null,
    val extractedText: String? = null
)

object AttachmentReader {
    suspend fun read(context: Context, uri: Uri, isImage: Boolean): ChatAttachment = withContext(Dispatchers.IO) {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "attachment"
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Не удалось открыть файл")
        if (isImage) {
            require(mime.startsWith("image/")) { "Выбранный файл не является изображением" }
            require(bytes.size <= 12 * 1024 * 1024) { "Изображение больше 12 МБ. Выберите файл меньшего размера" }
            ChatAttachment(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP), null)
        } else {
            val text = when {
                name.endsWith(".pdf", true) -> PDDocument.load(bytes).use { PDFTextStripper().getText(it) }
                name.endsWith(".docx", true) -> extractOfficeXml(bytes, "word/document.xml")
                name.endsWith(".pptx", true) -> extractOfficeSlides(bytes)
                name.endsWith(".xlsx", true) -> extractOfficeXml(bytes, "xl/sharedStrings.xml")
                name.endsWith(".txt", true) || name.endsWith(".md", true) || mime.startsWith("text/") -> bytes.toString(Charsets.UTF_8)
                else -> throw IllegalArgumentException("Формат не поддерживается. Используйте PDF, DOCX, PPTX, XLSX, TXT или MD")
            }
            require(text.isNotBlank()) { "В документе не найден извлекаемый текст. Возможно, PDF состоит из сканов" }
            ChatAttachment(name, mime, null, text.take(100_000))
        }
    }

    private fun extractOfficeSlides(bytes: ByteArray): String {
        val out = StringBuilder()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))) {
                    out.append(xmlText(zip.readBytes())).append("\n\n")
                }
            }
        }
        return out.toString()
    }

    private fun extractOfficeXml(bytes: ByteArray, wanted: String): String {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == wanted) return xmlText(zip.readBytes())
            }
        }
        return ""
    }

    private fun xmlText(bytes: ByteArray): String {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val doc = factory.newDocumentBuilder().parse(bytes.inputStream())
        val nodes = doc.getElementsByTagName("*")
        return buildString {
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node.localName == "t" || node.nodeName.endsWith(":t")) {
                    val value = node.textContent
                    if (!value.isNullOrBlank()) append(value).append(' ')
                }
            }
        }
    }
}

package nl.stijnvdpol.klinedash.core.export

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.OutputStream
import java.net.URLDecoder

private const val SESSION_COOKIE = "session"
private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
private const val ZIP_PATH = "/download/all.zip"
private const val ZIP_FILE_NAME = "trips.zip"
private val DOWNLOAD_INDEX_PATH = Regex("^/download/([0-9]{1,6})$")

/**
 * Routes one parsed request. Only listed files can be downloaded and only by their index in the
 * current sorted list; no part of the request ever becomes a file path.
 */
internal class ExportRequestHandler(
    private val auth: ExportAuth,
    private val files: TripFileSource,
) {
    fun handle(request: HttpRequest, out: OutputStream) {
        val writer = HttpResponseWriter(out)
        if (request.method != "GET" && request.method != "POST") {
            return methodNotAllowed(writer)
        }
        val path = request.path
        val isDownload = path == ZIP_PATH || DOWNLOAD_INDEX_PATH.matches(path)
        when {
            path == "/" && request.method == "GET" -> showHome(request, writer)
            path == "/login" && request.method == "POST" -> login(request, writer)
            isDownload && request.method == "GET" -> download(request, path, writer, out)
            path == "/" || path == "/login" || isDownload -> methodNotAllowed(writer)
            else -> writer.sendHtml(404, PageRenderer.messagePage("Not found", "This page does not exist."))
        }
    }

    private fun methodNotAllowed(writer: HttpResponseWriter) =
        writer.sendHtml(
            405,
            PageRenderer.messagePage("Not allowed", "This request is not supported."),
            listOf("Allow" to "GET, POST"),
        )

    private fun hasSession(request: HttpRequest) = auth.isValidSession(request.cookie(SESSION_COOKIE))

    private fun showHome(request: HttpRequest, writer: HttpResponseWriter) {
        if (!hasSession(request)) return writer.sendHtml(200, PageRenderer.loginPage())
        writer.sendHtml(200, PageRenderer.tripListPage(listOfferedFiles(files)))
    }

    private fun login(request: HttpRequest, writer: HttpResponseWriter) {
        val contentType = request.header("content-type")?.substringBefore(';')?.trim()?.lowercase()
        val pin = if (contentType == FORM_CONTENT_TYPE) parsePin(request.body) else null
        if (pin == null) {
            return writer.sendHtml(400, PageRenderer.loginPage("Enter the PIN shown on the head unit."))
        }
        when (val result = auth.login(pin)) {
            is ExportAuth.LoginResult.Success -> writer.sendRedirect(
                "/",
                listOf("Set-Cookie" to "$SESSION_COOKIE=${result.token}; HttpOnly; SameSite=Strict; Path=/"),
            )
            is ExportAuth.LoginResult.WrongPin -> writer.sendHtml(
                401,
                PageRenderer.loginPage("Wrong PIN. Attempts left: ${result.attemptsLeft}."),
            )
            ExportAuth.LoginResult.Locked -> writer.sendHtml(423, PageRenderer.lockedPage())
        }
    }

    private fun download(request: HttpRequest, path: String, writer: HttpResponseWriter, out: OutputStream) {
        if (!hasSession(request)) return writer.sendRedirect("/")
        // Re-resolve on every download: the list may have changed since the page was rendered.
        val offered = listOfferedFiles(files)
        if (path == ZIP_PATH) return sendZip(offered, writer, out)
        val file = DOWNLOAD_INDEX_PATH.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()?.let(offered::getOrNull)
        if (file == null) return notFound(writer)
        sendFile(file, writer, out)
    }

    private fun sendFile(file: File, writer: HttpResponseWriter, out: OutputStream) {
        try {
            FileInputStream(file).use { input ->
                val length = file.length()
                writer.beginStream("text/csv; charset=utf-8", length, attachment(sanitizeDownloadName(file.name)))
                streamBounded(input, out, length)
            }
        } catch (_: FileNotFoundException) {
            // The file vanished between listing and opening; nothing has been written yet.
            notFound(writer)
        }
    }

    private fun sendZip(offered: List<File>, writer: HttpResponseWriter, out: OutputStream) {
        if (offered.isEmpty()) return notFound(writer)
        writer.beginStream("application/zip", null, attachment(ZIP_FILE_NAME))
        streamZip(offered, out)
    }

    private fun notFound(writer: HttpResponseWriter) =
        writer.sendHtml(404, PageRenderer.messagePage("Not found", "This file is no longer available."))

    private fun attachment(safeName: String) = listOf("Content-Disposition" to "attachment; filename=\"$safeName\"")

    /** Returns the validated PIN from a urlencoded body, or null when absent or malformed. */
    private fun parsePin(body: ByteArray): String? {
        val raw = body.toString(Charsets.ISO_8859_1).split('&')
            .firstOrNull { it.substringBefore('=') == "pin" && '=' in it }
            ?.substringAfter('=') ?: return null
        val pin = try {
            URLDecoder.decode(raw, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val isValid = pin.length in 1..MAX_PIN_LENGTH && pin.none { it.code < 0x20 || it.code == 0x7F }
        return pin.takeIf { isValid }
    }
}

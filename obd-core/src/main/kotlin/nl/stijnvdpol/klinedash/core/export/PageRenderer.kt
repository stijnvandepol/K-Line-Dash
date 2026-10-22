package nl.stijnvdpol.klinedash.core.export

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val BYTES_PER_KB = 1024.0

private val STYLE = """
body{margin:0;padding:16px;background:#000;color:#fff;font-family:Bahnschrift,"Arial Narrow","Roboto Condensed","Helvetica Neue",Arial,sans-serif;font-size:18px}
main{max-width:640px;margin:0 auto}
h1{font-size:26px;letter-spacing:.12em;text-transform:uppercase;margin:8px 0 16px;border-left:6px solid #FFB000;padding-left:10px}
p{color:#8C8C8C}
.err{color:#FF2D2D}
form{display:flex;gap:8px}
input[type=password],input[type=text]{flex:1;min-width:0;background:#1C1C1C;color:#fff;border:1px solid #4D4D4D;padding:12px;font-size:22px;letter-spacing:.2em}
button,a.btn{background:#FFB000;color:#000;border:0;padding:12px 18px;font-size:18px;font-weight:bold;letter-spacing:.08em;text-transform:uppercase;text-decoration:none;display:inline-block}
ul{list-style:none;margin:0 0 20px;padding:0}
li{background:#1C1C1C;border-bottom:1px solid #2A2A2A;padding:12px;display:flex;justify-content:space-between;align-items:center;gap:12px}
li div{min-width:0;overflow-wrap:anywhere}
small{display:block;color:#8C8C8C}
li a{color:#FFB000;font-weight:bold;text-transform:uppercase;letter-spacing:.08em;text-decoration:none;white-space:nowrap}
""".trimIndent().replace("\n", "")

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(ZoneId.systemDefault())

/** Encodes text for HTML element and attribute context. */
internal fun htmlEncode(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

/** Builds the small self-contained pages. Every dynamic string goes through [htmlEncode]. */
internal object PageRenderer {

    fun loginPage(errorMessage: String? = null): String = page(
        "K-Line Dash export",
        buildString {
            append("<p>Enter the PIN shown on the head unit.</p>")
            if (errorMessage != null) append("<p class=\"err\">${htmlEncode(errorMessage)}</p>")
            append("<form method=\"post\" action=\"/login\">")
            append("<input type=\"password\" name=\"pin\" inputmode=\"numeric\" maxlength=\"$MAX_PIN_LENGTH\" ")
            append("autocomplete=\"off\" autofocus required>")
            append("<button type=\"submit\">Open</button></form>")
        },
    )

    fun lockedPage(): String = page(
        "Export locked",
        "<p class=\"err\">Too many wrong PINs. Stop and restart the export on the head unit to try again.</p>",
    )

    fun messagePage(title: String, message: String): String =
        page(title, "<p>${htmlEncode(message)}</p><p><a href=\"/\" style=\"color:#FFB000\">Back</a></p>")

    fun tripListPage(files: List<File>): String = page(
        "Trips",
        buildString {
            if (files.isEmpty()) {
                append("<p>No trips recorded yet.</p>")
                return@buildString
            }
            append("<ul>")
            files.forEachIndexed { index, file -> append(row(index, file)) }
            append("</ul><a class=\"btn\" href=\"/download/all.zip\">Download all as ZIP</a>")
        },
    )

    private fun row(index: Int, file: File): String {
        val sizeKb = String.format(Locale.US, "%.1f KB", file.length() / BYTES_PER_KB)
        val modified = TIME_FORMAT.format(Instant.ofEpochMilli(file.lastModified()))
        return "<li><div>${htmlEncode(file.name)}<small>$sizeKb &middot; $modified</small></div>" +
            "<a href=\"/download/$index\">Download</a></li>"
    }

    private fun page(title: String, body: String): String =
        "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>${htmlEncode(title)}</title><style>$STYLE</style></head>" +
            "<body><main><h1>${htmlEncode(title)}</h1>$body</main></body></html>"
}

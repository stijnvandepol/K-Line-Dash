package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.ui.graphics.Color
import nl.stijnvdpol.klinedash.data.ClusterStyle
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

private const val NAME_KEY = "name"
private const val COLORS_KEY = "colors"
private const val CLUSTER_KEY = "cluster"
private const val NUMERALS_KEY = "numerals"
private const val ACCENT_KEY = "accent"
private const val FACE_KEY = "face"
private const val FACE_INK_KEY = "faceInk"
private const val RING_KEY = "ring"
private const val NEEDLE_KEY = "needle"
private const val MAX_NAME_LENGTH = 24
private const val MAX_SHOWN_VALUE_LENGTH = 16
private const val OPAQUE = 0xFF000000.toInt()
private const val HEX_RADIX = 16

/**
 * A theme is two levels deep. The JSON parser recurses per level, so a file nested thousands of
 * levels deep (which still fits in the size limit) would overflow its stack and end the app.
 */
private const val MAX_NESTING = 8
private val HexColor = Regex("^#[0-9A-Fa-f]{6}$")

/** The colours every theme file must define, in the order of docs/themes.md. */
private val RequiredTokens = listOf("background", "surface", "rule", "ink", "inkMuted", "inkFaint", "ok", "caution", "danger")

/** The colours a theme file may leave out; each then follows a base token (see [KLineColors]). */
private val OptionalTokens = listOf(ACCENT_KEY, FACE_KEY, FACE_INK_KEY, RING_KEY, NEEDLE_KEY)

/** A theme as shown in the list; [id] is stable (file name based) and is what the settings store. */
data class ThemeDefinition(
    val id: String,
    val name: String,
    val colors: KLineColors,
    val isBuiltIn: Boolean,
    val look: ClusterLook = ClusterLook(),
)

sealed interface ThemeParseResult {
    data class Valid(val name: String, val colors: KLineColors, val look: ClusterLook = ClusterLook()) : ThemeParseResult

    /** [reason] is written for the user: what is wrong and what to change. */
    data class Invalid(val reason: String) : ThemeParseResult
}

/**
 * Parses and validates the text of a theme file (format: docs/themes.md). Anything wrong gives
 * [ThemeParseResult.Invalid] with the first problem found; a file is never half-accepted.
 */
fun parseTheme(text: String): ThemeParseResult {
    if (isNestedDeeperThan(text, MAX_NESTING)) {
        return ThemeParseResult.Invalid("Not a theme: lists or objects are nested too deeply. A theme has only \"$NAME_KEY\" and \"$COLORS_KEY\".")
    }
    val root = try {
        JSONObject(text)
    } catch (e: JSONException) {
        return ThemeParseResult.Invalid("Not valid JSON. Check for a missing comma, quote or bracket.")
    }
    val name = (root.opt(NAME_KEY) as? String)?.trim().orEmpty()
    if (name.isEmpty()) return ThemeParseResult.Invalid("Missing \"$NAME_KEY\": give the theme a short name.")
    if (name.length > MAX_NAME_LENGTH) return ThemeParseResult.Invalid("The name is longer than $MAX_NAME_LENGTH characters.")
    val colorObject = root.optJSONObject(COLORS_KEY)
        ?: return ThemeParseResult.Invalid("Missing \"$COLORS_KEY\": an object with the color tokens.")

    val values = HashMap<String, Color>()
    for (token in RequiredTokens + OptionalTokens) {
        val raw = colorObject.opt(token)
        if (raw == null) {
            if (token in OptionalTokens) continue
            return ThemeParseResult.Invalid("Missing color \"$token\".")
        }
        values[token] = parseHexColor(raw) ?: return ThemeParseResult.Invalid(
            "Color \"$token\" is ${describe(raw)}: write it as #RRGGBB, for example #FF8800.",
        )
    }
    val ink = values.getValue("ink")
    val colors = KLineColors(
        background = values.getValue("background"),
        surface = values.getValue("surface"),
        rule = values.getValue("rule"),
        ink = ink,
        inkMuted = values.getValue("inkMuted"),
        inkFaint = values.getValue("inkFaint"),
        ok = values.getValue("ok"),
        caution = values.getValue("caution"),
        danger = values.getValue("danger"),
        accent = values[ACCENT_KEY] ?: ink,
    ).withOptionalDialColors(values)
    val contrast = contrastRatio(colors.inkMuted, colors.background)
    if (contrast < MIN_LABEL_CONTRAST) {
        return ThemeParseResult.Invalid(
            "Labels are too faint: inkMuted on background has a contrast of ${String.format(Locale.US, "%.1f", contrast)}:1, " +
                "at least $MIN_LABEL_CONTRAST:1 is needed. Move inkMuted further away from the background color.",
        )
    }
    val faceProblem = dialContrastProblem(colors, values)
    if (faceProblem != null) return ThemeParseResult.Invalid(faceProblem)
    val look = parseLook(root) ?: return ThemeParseResult.Invalid(unknownLookReason(root))
    return ThemeParseResult.Valid(name, colors, look)
}

/** The dial colours the file set, over the defaults that follow the base tokens. */
private fun KLineColors.withOptionalDialColors(values: Map<String, Color>): KLineColors {
    val face = values[FACE_KEY] ?: background
    val faceInk = values[FACE_INK_KEY] ?: ink
    val ring = values[RING_KEY] ?: rule
    return copy(face = face, faceInk = faceInk, ring = ring, needle = values[NEEDLE_KEY] ?: accent)
}

/**
 * Numbers and ticks on a dial are read at a glance like the labels are. Only checked when the file
 * sets [FACE_KEY] or [FACE_INK_KEY]: a theme without them draws its dials on the background in ink.
 */
private fun dialContrastProblem(colors: KLineColors, values: Map<String, Color>): String? {
    if (FACE_KEY !in values && FACE_INK_KEY !in values) return null
    val contrast = contrastRatio(colors.faceInk, colors.face)
    if (contrast >= MIN_LABEL_CONTRAST) return null
    return "Dial numbers are too faint: faceInk on face has a contrast of ${String.format(Locale.US, "%.1f", contrast)}:1, " +
        "at least $MIN_LABEL_CONTRAST:1 is needed. Move faceInk further away from the face color."
}

/** The cluster style and numerals the file names (the defaults when it names none); null when one of them is unknown. */
private fun parseLook(root: JSONObject): ClusterLook? {
    val defaults = ClusterLook()
    val style = if (root.has(CLUSTER_KEY)) (root.opt(CLUSTER_KEY) as? String)?.let(ClusterStyle::fromId) else defaults.style
    val numerals = if (root.has(NUMERALS_KEY)) (root.opt(NUMERALS_KEY) as? String)?.let(NumeralFace::fromId) else defaults.numerals
    return if (style == null || numerals == null) null else ClusterLook(style, numerals)
}

private fun unknownLookReason(root: JSONObject): String {
    if (root.has(CLUSTER_KEY) && (root.opt(CLUSTER_KEY) as? String)?.let(ClusterStyle::fromId) == null) {
        return "Unknown cluster ${describe(root.opt(CLUSTER_KEY)!!)}: use ${ClusterStyle.entries.joinToString(", ") { it.id }}."
    }
    return "Unknown numerals ${describe(root.opt(NUMERALS_KEY)!!)}: use ${NumeralFace.entries.joinToString(", ") { it.id }}."
}

/**
 * Whether [text] opens more than [limit] objects or arrays inside each other. Brackets inside
 * strings do not count; the parser accepts strings in single and in double quotes, so both are skipped.
 */
private fun isNestedDeeperThan(text: String, limit: Int): Boolean {
    var depth = 0
    var quote: Char? = null
    var isEscaped = false
    for (c in text) {
        if (quote != null) {
            when {
                isEscaped -> isEscaped = false
                c == '\\' -> isEscaped = true
                c == quote -> quote = null
            }
            continue
        }
        when (c) {
            '"', '\'' -> quote = c
            '{', '[' -> if (++depth > limit) return true
            '}', ']' -> depth--
        }
    }
    return false
}

private fun parseHexColor(raw: Any): Color? {
    val hex = raw as? String ?: return null
    if (!HexColor.matches(hex)) return null
    return Color(OPAQUE or hex.substring(1).toInt(HEX_RADIX))
}

private fun describe(raw: Any): String {
    val shown = raw.toString().take(MAX_SHOWN_VALUE_LENGTH)
    return if (raw is String) "\"$shown\"" else shown
}

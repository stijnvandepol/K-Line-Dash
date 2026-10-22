package nl.stijnvdpol.klinedash.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A typed view on a group of rows in the key/value `settings` table. [decode] turns all rows into
 * the value (missing or unreadable rows give the default, see [parsed]); [encode] gives the rows to
 * store, where a null value means "remove the row". Shared by every settings repository so that the
 * read-modify-write locking exists once.
 */
class TypedSettings<T>(
    private val dao: SettingsDao,
    private val decode: (Map<String, String>) -> T,
    private val encode: (T) -> Map<String, String?>,
) {
    private val writeLock = Mutex()

    /** Re-emits on every row change in the table (also other groups' rows), hence the distinctUntilChanged. */
    val flow: Flow<T> =
        dao.observeAll().map { rows -> decode(rows.associate { it.key to it.value }) }.distinctUntilChanged()

    /** Read-modify-write under a lock; only rows that actually changed are written. */
    suspend fun update(transform: (T) -> T) {
        writeLock.withLock {
            val old = decode(dao.getAll().associate { it.key to it.value })
            val new = transform(old)
            if (new == old) return
            val oldRows = encode(old)
            for ((key, value) in encode(new)) {
                if (oldRows[key] == value) continue
                if (value == null) dao.delete(key) else dao.upsert(SettingEntry(key, value))
            }
        }
    }
}

private const val TAG = "TypedSettings"

/**
 * Missing rows mean "never set" and give [default]. A row that does not parse (written by a newer
 * app version, or corrupted) is treated the same way, but it is logged instead of passing silently.
 */
fun <T> Map<String, String>.parsed(key: String, default: T, parse: (String) -> T?): T {
    val raw = this[key] ?: return default
    return parse(raw) ?: default.also { Log.w(TAG, "Unreadable setting $key=\"$raw\", using $it") }
}

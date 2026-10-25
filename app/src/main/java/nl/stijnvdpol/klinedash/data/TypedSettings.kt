package nl.stijnvdpol.klinedash.data

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A typed view on a group of rows in the key/value `settings` table. [decode] turns all rows into
 * the value (missing or unreadable rows give the default, see [parsed]); [encode] gives the rows to
 * store, where a null value means "remove the row". Shared by every settings repository so that the
 * read-modify-write locking exists once. Rows are decoded on [decodeDispatcher]; tests pass their
 * own so decoding runs in virtual time instead of racing them on a real thread.
 */
class TypedSettings<T>(
    private val dao: SettingsDao,
    private val decode: (Map<String, String>) -> T,
    private val encode: (T) -> Map<String, String?>,
    decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val writeLock = Mutex()

    /**
     * Re-emits on every row change in the table (also other groups' rows), hence the
     * distinctUntilChanged. Decoded off the main thread: the dashboard layout is JSON, and the table
     * changes with every wake or sleep broadcast the service notes down.
     */
    val flow: Flow<T> = dao.observeAll()
        .map { rows -> decode(rows.associate { it.key to it.value }) }
        .distinctUntilChanged()
        .flowOn(decodeDispatcher)

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

/**
 * The settings table behind one shared query: every [TypedSettings] built on this DAO listens to the
 * same [observeAll] instead of running its own `SELECT * FROM settings` (a dozen repositories would
 * otherwise read and re-read the table at startup). The query starts right away, in [scope], and the
 * latest rows are replayed to a late collector, so a repository that starts collecting after the
 * table was read gets its value without another query. Everything but [observeAll] goes straight
 * to [dao].
 *
 * [beforeFirstRead] runs once before the first read, for a migration of old rows that no reader may
 * see in the old form. If it throws, the shared flow ends and the exception goes to [scope]'s handler.
 */
class SharedSettingsDao(
    private val dao: SettingsDao,
    scope: CoroutineScope,
    beforeFirstRead: suspend () -> Unit = {},
) : SettingsDao by dao {
    private val rows: SharedFlow<List<SettingEntry>> = flow {
        beforeFirstRead()
        emitAll(dao.observeAll())
    }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    override fun observeAll(): Flow<List<SettingEntry>> = rows
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

package nl.stijnvdpol.klinedash.data

import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import nl.stijnvdpol.klinedash.core.geo.Gate
import nl.stijnvdpol.klinedash.core.geo.LapGates
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.core.geo.isValidPosition
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "Track"
private const val MAX_TRACKS = 200
private const val MAX_HEADING_DEG = 360.0
private const val MAX_WIDTH_M = 500.0

/** Most sector lines on one track. */
const val MAX_SECTOR_LINES = 20

/**
 * A timing line as the user set it: the point on the road, the driving direction there and the
 * width. The line is perpendicular to [headingDeg] and only counts in that direction (see [Gate]).
 * Storing the setting instead of the two end points keeps it editable ("wider", "turn around").
 */
data class GateSpec(val lat: Double, val lon: Double, val headingDeg: Double, val widthM: Double = Gate.DEFAULT_WIDTH_M) {
    init {
        require(isValidPosition(lat, lon)) { "Not a valid position (latitude or longitude out of range)" }
        require(headingDeg.isFinite() && headingDeg >= 0.0 && headingDeg < MAX_HEADING_DEG) { "Heading out of range: $headingDeg" }
        require(widthM.isFinite() && widthM > 0.0 && widthM <= MAX_WIDTH_M) { "Gate width out of range: $widthM" }
    }

    fun toGate(): Gate = Gate.fromPointAndHeading(LatLon(lat, lon), headingDeg, widthM)
}

/**
 * A named circuit: one start/finish line and optional sector lines in driving order. Laps are not
 * stored; they are derived from the fixes of a trip for the gates of its track. Created by the
 * user ("set start/finish here"), see LapSessions.
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startLat: Double,
    val startLon: Double,
    val startHeadingDeg: Double,
    val startWidthM: Double,
    /** JSON array of `{lat, lon, heading, width}` objects, see [encodeSectors]. */
    val sectorsJson: String,
    val createdMs: Long,
) {
    val startFinish: GateSpec get() = GateSpec(startLat, startLon, startHeadingDeg, startWidthM)

    /** Sector lines in driving order; an unreadable stored list gives none (and is logged). */
    val sectors: List<GateSpec> get() = decodeSectors(sectorsJson)

    fun lapGates(): LapGates = LapGates(startFinish.toGate(), sectors.map { it.toGate() })
}

/** A new track from its gates; [sectors] are the sector lines in driving order. */
fun newTrack(name: String, startFinish: GateSpec, sectors: List<GateSpec>, createdMs: Long): Track {
    require(name.isNotBlank()) { "A track needs a name" }
    require(sectors.size <= MAX_SECTOR_LINES) { "At most $MAX_SECTOR_LINES sector lines" }
    return Track(
        name = name.trim(), startLat = startFinish.lat, startLon = startFinish.lon,
        startHeadingDeg = startFinish.headingDeg, startWidthM = startFinish.widthM,
        sectorsJson = encodeSectors(sectors), createdMs = createdMs,
    )
}

private object SectorKeys {
    const val LAT = "lat"
    const val LON = "lon"
    const val HEADING = "heading"
    const val WIDTH = "width"
}

fun encodeSectors(sectors: List<GateSpec>): String {
    val array = JSONArray()
    for (sector in sectors) {
        array.put(
            JSONObject().put(SectorKeys.LAT, sector.lat).put(SectorKeys.LON, sector.lon)
                .put(SectorKeys.HEADING, sector.headingDeg).put(SectorKeys.WIDTH, sector.widthM),
        )
    }
    return array.toString()
}

/** The sectors in [raw]; an unreadable list gives none, because a half-read list would shift the sector order. */
fun decodeSectors(raw: String): List<GateSpec> = try {
    val array = JSONArray(raw)
    List(array.length()) { index ->
        val json = array.getJSONObject(index)
        GateSpec(json.getDouble(SectorKeys.LAT), json.getDouble(SectorKeys.LON), json.getDouble(SectorKeys.HEADING), json.getDouble(SectorKeys.WIDTH))
    }
} catch (e: JSONException) {
    Log.w(TAG, "Unreadable sector list, using none", e)
    emptyList()
} catch (e: IllegalArgumentException) {
    Log.w(TAG, "Invalid sector in the list, using none", e)
    emptyList()
}

@Dao
interface TrackDao {
    /** Returns the generated id. */
    @Insert
    suspend fun insert(track: Track): Long

    @Query("UPDATE tracks SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE tracks SET sectorsJson = :sectorsJson WHERE id = :id")
    suspend fun setSectors(id: Long, sectorsJson: String)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: Long): Track?

    /** Newest first, bounded: the user keeps a handful of circuits. */
    @Query("SELECT * FROM tracks ORDER BY createdMs DESC LIMIT $MAX_TRACKS")
    fun observeAll(): Flow<List<Track>>

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

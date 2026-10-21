package nl.stijnvdpol.klinedash.data

import nl.stijnvdpol.klinedash.core.elm.ProtocolStore

/** Stores the detected ELM protocol per adapter under "protocol.<adapterKey>". */
class RoomProtocolStore(private val dao: SettingsDao) : ProtocolStore {

    override suspend fun loadProtocol(adapterKey: String): String? = dao.get(key(adapterKey))?.value

    override suspend fun saveProtocol(adapterKey: String, protocolNumber: String) {
        dao.upsert(SettingEntry(key(adapterKey), protocolNumber))
    }

    override suspend fun clearProtocol(adapterKey: String) {
        dao.delete(key(adapterKey))
    }

    private fun key(adapterKey: String) = "protocol.$adapterKey"
}

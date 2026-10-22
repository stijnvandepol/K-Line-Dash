package nl.stijnvdpol.klinedash.core.export

import java.security.MessageDigest
import java.security.SecureRandom

private const val SESSION_TOKEN_BYTES = 16
private const val MAX_SESSIONS = 16
internal const val MAX_PIN_LENGTH = 32

/**
 * PIN check, lockout and session tokens for one export run. A new instance is created on every
 * start so restarting the export on the head unit resets the lockout.
 */
internal class ExportAuth(
    pin: String,
    private val maxPinAttempts: Int,
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface LoginResult {
        data class Success(val token: String) : LoginResult
        data class WrongPin(val attemptsLeft: Int) : LoginResult
        data object Locked : LoginResult
    }

    // Compare fixed-length digests so neither content nor length of the PIN leaks through timing.
    private val pinDigest = sha256(pin)
    private var failedAttempts = 0
    private val sessions = LinkedHashSet<String>()

    @Synchronized
    fun login(candidate: String): LoginResult {
        if (failedAttempts >= maxPinAttempts) return LoginResult.Locked
        if (!MessageDigest.isEqual(pinDigest, sha256(candidate))) {
            failedAttempts++
            return LoginResult.WrongPin(maxPinAttempts - failedAttempts)
        }
        val token = newToken()
        sessions.add(token)
        if (sessions.size > MAX_SESSIONS) sessions.remove(sessions.first())
        return LoginResult.Success(token)
    }

    @Synchronized
    fun isValidSession(token: String?): Boolean = token != null && token in sessions

    private fun newToken(): String {
        val bytes = ByteArray(SESSION_TOKEN_BYTES).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
}

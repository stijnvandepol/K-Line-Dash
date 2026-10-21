package nl.stijnvdpol.klinedash.core

/** Minimal assertion helpers; the module only has JUnit 4 on the test classpath (no kotlin-test). */

/** Asserts [value] is a [T] and returns it, so callers can use it without a manual cast. */
inline fun <reified T : Any> assertIs(value: Any?, message: String? = null): T {
    if (value !is T) {
        val prefix = if (message != null) "$message: " else ""
        throw AssertionError("${prefix}expected ${T::class.java.simpleName} but was $value")
    }
    return value
}

/** Runs [block] and returns the [T] it throws; fails if it throws something else or nothing. */
inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("expected ${T::class.java.simpleName} but got $e", e)
    }
    throw AssertionError("expected ${T::class.java.simpleName} but nothing was thrown")
}

package org.odyssey.mod

import org.slf4j.LoggerFactory

internal object OdysseyDiagnostics {
    val logger = LoggerFactory.getLogger("Odyssey Mod")

    inline fun <T> callback(context: String, action: () -> T): T = try {
        action()
    } catch (error: Throwable) {
        logger.error("[Odyssey Mod] {} failed", context, error)
        throw error
    }
}

package dev.mcnet.voice

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Kept apart from the mod initializer so the relay can be tested without Fabric on the classpath. */
object VoiceLog {
    const val MOD_ID = "mcnet-voice"

    @JvmField
    val logger: Logger = LoggerFactory.getLogger(MOD_ID)
}

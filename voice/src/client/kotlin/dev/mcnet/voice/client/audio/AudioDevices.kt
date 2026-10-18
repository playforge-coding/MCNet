package dev.mcnet.voice.client.audio

import org.lwjgl.openal.ALC10
import org.lwjgl.openal.ALC11
import org.lwjgl.openal.ALUtil
import org.lwjgl.openal.EnumerateAllExt
import org.lwjgl.system.MemoryUtil

/**
 * Device enumeration through the OpenAL Minecraft already ships. No extra
 * native code: LWJGL's OpenAL binding is on every client's classpath.
 */
object AudioDevices {
    /** Playback devices. Empty if the driver cannot enumerate. */
    fun outputs(): List<String> {
        val token =
            if (ALC10.alcIsExtensionPresent(MemoryUtil.NULL, "ALC_ENUMERATE_ALL_EXT")) {
                EnumerateAllExt.ALC_ALL_DEVICES_SPECIFIER
            } else {
                ALC10.ALC_DEVICE_SPECIFIER
            }
        return runCatching { ALUtil.getStringList(MemoryUtil.NULL, token) }.getOrNull().orEmpty()
    }

    /** Capture devices. */
    fun inputs(): List<String> =
        runCatching { ALUtil.getStringList(MemoryUtil.NULL, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER) }.getOrNull().orEmpty()

    /**
     * Strips the "OpenAL Soft on " prefix the bundled driver puts in front of
     * every device name. Purely cosmetic; the full name is what gets stored.
     */
    fun displayName(device: String): String = device.removePrefix("OpenAL Soft on ")
}

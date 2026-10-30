package dev.mcnet.voice.client.audio

import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc

/**
 * Where the listener and each speaker were as of the last frame, in world
 * coordinates. Built on the render thread, read on the audio thread; a new
 * one replaces the old wholesale, so nothing here is ever mutated.
 *
 * OpenAL works in single precision and Minecraft coordinates run into the
 * millions, so the listener stays at the origin and every source is placed
 * at its offset from the listener instead. Minecraft's own sound engine
 * hands OpenAL world-space vectors as they are, so no axes need swapping.
 */
class Spatial(
    /** Blocks at which a voice has faded to silence. */
    val range: Float,
    private val listener: Vec3,
    /** Where the camera looks, and which way is up for it. */
    val forward: Vector3fc,
    val up: Vector3fc,
    private val speakers: Map<Int, Vec3>,
) {
    /** The speaker's offset from the listener, or null if the game does not know where they are. */
    fun offsetOf(speaker: Int): Vec3? = speakers[speaker]?.subtract(listener)
}

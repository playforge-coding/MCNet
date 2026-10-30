package dev.mcnet.voice.client.audio

import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc

/**
 * Where voices should come from, as of the last frame. Built on the render
 * thread, read on the audio thread; a new one replaces the old wholesale,
 * so nothing here is ever mutated, and [Playback] can tell a new frame from
 * the last one by identity alone.
 */
sealed interface Placement {
    /**
     * Not known yet: the host has not announced its range, or has and no
     * frame has placed anyone since. Nothing plays, because playing a voice
     * flat on a world with proximity would put a distant speaker in your ear.
     */
    data object Pending : Placement

    /** The host has no proximity: every voice plays at the listener, unattenuated. */
    data object Flat : Placement

    /**
     * Voices in space. OpenAL works in single precision and Minecraft
     * coordinates run into the millions, so the listener stays at the origin
     * and every source is placed at its offset from the listener instead.
     * Minecraft's own sound engine hands OpenAL world-space vectors as they
     * are, so no axes need swapping.
     *
     * The listener is the local player's head, not the camera, because that
     * is what the host measures from. In third person the camera sits behind
     * the player, and measuring from it would let a voice the host has
     * already stopped forwarding still be audible, so it would cut instead of
     * fading. The camera still decides which way the listener faces.
     */
    class Positional(
        /** Blocks at which a voice has faded to silence. */
        val range: Float,
        /** Which way the camera looks, and which way is up for it. */
        val forward: Vector3fc,
        val up: Vector3fc,
        private val listener: Vec3,
        private val speakers: Map<Int, Vec3>,
    ) : Placement {
        /** The speaker's offset from the listener, or null if this client has never seen them. */
        fun offsetOf(speaker: Int): Vec3? = speakers[speaker]?.subtract(listener)
    }
}

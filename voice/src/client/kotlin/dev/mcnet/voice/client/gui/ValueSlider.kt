package dev.mcnet.voice.client.gui

import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.network.chat.Component

/**
 * A slider over a real-valued range. Vanilla's slider works in 0 to 1 and
 * leaves the mapping to subclasses; this is that subclass, once.
 */
class ValueSlider(
    width: Int,
    private val min: Double,
    private val max: Double,
    initial: Double,
    private val label: (Double) -> Component,
    private val onChange: (Double) -> Unit,
) : AbstractSliderButton(0, 0, width, 20, label(initial), ((initial - min) / (max - min)).coerceIn(0.0, 1.0)) {
    val current: Double get() = min + value * (max - min)

    override fun updateMessage() {
        message = label(current)
    }

    override fun applyValue() {
        onChange(current)
    }
}

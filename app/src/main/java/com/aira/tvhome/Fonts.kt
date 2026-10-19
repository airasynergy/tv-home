package com.aira.tvhome

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

/** Inter, bundled in assets/fonts (SIL Open Font License — see Inter-LICENSE.txt). */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private fun load(ctx: Context, name: String): Typeface = cache.getOrPut(name) {
        try {
            Typeface.createFromAsset(ctx.assets, "fonts/Inter-$name.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }

    fun light(ctx: Context) = load(ctx, "Light")
    fun regular(ctx: Context) = load(ctx, "Regular")
    fun medium(ctx: Context) = load(ctx, "Medium")
    fun semibold(ctx: Context) = load(ctx, "SemiBold")
    fun bold(ctx: Context) = load(ctx, "Bold")

    /**
     * Applies Inter with display-style tracking: big text sits tighter, small text a touch looser,
     * the way Apple sets SF Pro.
     */
    fun style(view: TextView, face: Typeface, tracking: Float, tabularNumbers: Boolean = false) {
        view.typeface = face
        view.letterSpacing = tracking
        view.paint.isSubpixelText = true
        // cv11 = single-storey "a", ss03 = round quotes; tnum keeps the clock from jiggling.
        view.fontFeatureSettings = if (tabularNumbers) "'tnum', 'cv11'" else "'cv11'"
    }
}

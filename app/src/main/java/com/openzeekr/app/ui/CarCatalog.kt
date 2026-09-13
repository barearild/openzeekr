package com.openzeekr.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Per-model paint palettes + white render. Zeekr publishes no official hex, so these
 * are sourced/estimated (see ZEEKR_COLORS.md). The colour tints the hero "identity
 * card"; the white render (already transparent) sits on top, loaded at runtime from
 * assets/cars/ (gitignored — those are Zeekr press renders; supply your own locally).
 * The app keys this off the vehicle-list `modelName`/`innerCode` + `colorName`.
 */
data class PaintColor(val name: String, val color: Color, val finish: String)

data class CarModel(
    val key: String,
    val displayName: String,
    /** Asset path under assets/, e.g. "cars/car_7gt.webp" (may be absent → no render). */
    val renderAsset: String,
    val colors: List<PaintColor>,
)

object CarCatalog {
    private fun c(rgb: Long) = Color(0xFF000000 or rgb)

    val models: List<CarModel> = listOf(
        CarModel("001", "Zeekr 001", "cars/car_001.webp", listOf(
            PaintColor("Crystal White", c(0xDEDEDE), "Pearl"),
            PaintColor("Tech Grey", c(0x3B3B3E), "Metallic"),
            PaintColor("Phantom Black", c(0x2B2B2B), "Metallic"),
            PaintColor("Electric Blue", c(0x35AACB), "Metallic"),
            PaintColor("Energy Orange", c(0xFF7F00), "Metallic"),
            PaintColor("Forest Green", c(0x2E3B2E), "Metallic"),
            PaintColor("Mineral Green", c(0x5A6B5A), "Metallic"),
            PaintColor("Lava Grey", c(0x6E6F72), "Metallic"),
        )),
        CarModel("X", "Zeekr X", "cars/car_x.webp", listOf(
            PaintColor("Crystal White", c(0xF3F6FA), "Pearl"),
            PaintColor("Mist Grey", c(0x9A9CA0), "Metallic"),
            PaintColor("Grid Grey", c(0x65666A), "Metallic"),
            PaintColor("Palace Beige", c(0xE0D2AF), "Metallic"),
            PaintColor("Pine Green", c(0x3C4A32), "Metallic"),
            PaintColor("Matt Khaki Green", c(0x909602), "Matte"),
        )),
        CarModel("7X", "Zeekr 7X", "cars/car_7x.webp", listOf(
            PaintColor("Forest Green", c(0x436238), "Metallic"),
            PaintColor("Crystal White", c(0xBDC0C3), "Pearl"),
            PaintColor("Onyx Black", c(0x111111), "Metallic"),
            PaintColor("Tech Grey", c(0x868686), "Metallic"),
            PaintColor("Brookblue", c(0x677FA3), "Two-tone"),
        )),
        CarModel("7GT", "Zeekr 7GT", "cars/car_7gt.webp", listOf(
            PaintColor("Mystic Lilac", c(0xB9A7C4), "Pearl"),
            PaintColor("Crystal White", c(0xE9ECEF), "Pearl"),
            PaintColor("Glacier Silver", c(0xC4C8CC), "Metallic"),
            PaintColor("Tech Grey", c(0x7C7E82), "Metallic"),
            PaintColor("Onyx Black", c(0x141414), "Metallic"),
        )),
        CarModel("9X", "Zeekr 9X", "cars/car_9x.webp", listOf(
            PaintColor("Onyx Black", c(0x1A1A1A), "Metallic"),
            PaintColor("Crystal White", c(0xEDEFF2), "Pearl"),
            PaintColor("Lava Grey", c(0x6C6E71), "Metallic"),
            PaintColor("Glacier Silver", c(0xC6CACE), "Metallic"),
            PaintColor("Wilderness Green", c(0x4A5648), "Metallic"),
        )),
    )

    private val byKey = models.associateBy { it.key }

    /** Resolve from a vehicle-list modelName / seriesName / innerCode (e.g. "CX1E" = 7X). */
    fun forModel(name: String?): CarModel {
        val n = name?.uppercase()?.replace(" ", "") ?: return byKey.getValue("7GT")
        return when {
            "7GT" in n || "007GT" in n -> byKey.getValue("7GT")
            "CX1E" in n || "7X" in n -> byKey.getValue("7X")
            "9X" in n -> byKey.getValue("9X")
            "001" in n -> byKey.getValue("001")
            n == "X" || "ZEEKRX" in n -> byKey.getValue("X")
            else -> byKey.getValue("7GT")
        }
    }

    /** Look up a colour by its reported name within a model (falls back to the first). */
    fun colorFor(model: CarModel, colorName: String?): PaintColor {
        if (colorName != null) {
            model.colors.firstOrNull { it.name.equals(colorName, ignoreCase = true) }?.let { return it }
        }
        return model.colors.first()
    }
}

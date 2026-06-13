package com.nash.core.model

enum class AnonymizationModeEnum(val displayText: String) {
    BLUR("Gaussian blur"),
    PIXELATE("Pixelate"),
    BLACKBOX("black box"),
    BOUNDING("bounding box")
}

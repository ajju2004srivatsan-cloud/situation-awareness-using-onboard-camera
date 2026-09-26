package com.sa.edge

object ModeDetector {
    enum class Mode { PHONE, G20 }

    fun isLikelyG20(): Boolean {
        val model = (android.os.Build.MODEL ?: "").lowercase()
        val device = (android.os.Build.DEVICE ?: "").lowercase()
        val product = (android.os.Build.PRODUCT ?: "").lowercase()
        val blob = "$model $device $product"
        return blob.contains("g20") || blob.contains("skydroid")
    }

    fun resolve(preference: String): Mode = when (preference) {
        "phone" -> Mode.PHONE
        "g20" -> Mode.G20
        else -> if (isLikelyG20()) Mode.G20 else Mode.PHONE
    }
}

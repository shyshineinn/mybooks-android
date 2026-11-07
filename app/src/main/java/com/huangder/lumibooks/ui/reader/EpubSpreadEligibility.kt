package com.huangder.lumibooks.ui.reader

internal fun originalEpubSpreadEligible(
    enabled: Boolean, isEpub: Boolean, widthDp: Int, heightDp: Int,
    eInk: Boolean, transition: String
): Boolean = enabled && isEpub && !eInk && widthDp >= 600 &&
    heightDp > 0 && heightDp.toLong() * 4 <= widthDp.toLong() * 5 && transition !in setOf("continuous", "scroll")

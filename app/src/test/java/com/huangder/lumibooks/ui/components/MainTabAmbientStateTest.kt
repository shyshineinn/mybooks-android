package com.huangder.lumibooks.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabAmbientStateTest {
    @Test
    fun selectedPageDrivesSharedSpecAndScrollOffset() {
        val state = MainTabAmbientState(3)
        state.update(0, listOf(Color.Red), 1f)
        state.update(1, listOf(Color.Blue), .55f)

        state.selectedPage = 1

        assertEquals(listOf(Color.Blue), state.spec(1).colors)
        assertEquals(.55f, state.spec(1).intensity, 0f)
    }

    @Test
    fun unknownPagesFallBackToAnEmptySpec() {
        val state = MainTabAmbientState(3)

        assertEquals(MainTabAmbientSpec(), state.spec(9))
    }
}

package com.music.bitchord

import com.music.bitchord.ui.components.SupportDonations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportDonationsTest {

    @Test
    fun `rails match the readme addresses`() {
        val rails = SupportDonations.rails
        assertEquals(3, rails.size)
        val byLabel = rails.associateBy { it.label }
        assertEquals(
            "nano_1zqdw3qf1z8k3jx8jintaiwpo3yz7zqh1me4ph5j439ts8hsppx8dzy4xcsz",
            byLabel["Nano (XNO)"]?.address,
        )
        assertEquals(
            "0x3f262ee685ced4a8270cece45ebdfdb2b18f54b5",
            byLabel["USDC on Base"]?.address,
        )
        assertTrue(byLabel["Zcash (ZEC)"]?.address?.startsWith("u1") == true)
        assertTrue(rails.all { it.address.isNotBlank() && it.note.isNotBlank() })
    }
}

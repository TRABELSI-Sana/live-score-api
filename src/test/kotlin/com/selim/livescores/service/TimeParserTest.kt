package com.selim.livescores.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TimeParserTest {
    @Test
    fun `normalize trims spaces and quotes`() {
        assertEquals("45+2", TimeParser.normalize(" 45 + 2 ' "))
        assertEquals("90", TimeParser.normalize("90'"))
    }

    @Test
    fun `parse minute handles regular and added time`() {
        assertEquals(4500, TimeParser.parseMinute("45"))
        assertEquals(4502, TimeParser.parseMinute("45+2"))
        assertEquals(9003, TimeParser.parseMinute("90+3'"))
    }

    @Test
    fun `parse minute returns null on empty or invalid`() {
        assertNull(TimeParser.parseMinute(null))
        assertNull(TimeParser.parseMinute(""))
        assertNull(TimeParser.parseMinute("HT"))
    }
}

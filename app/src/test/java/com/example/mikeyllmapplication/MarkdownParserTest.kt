package com.example.mikeyllmapplication

import androidx.compose.ui.graphics.Color
import com.example.mikeyllmapplication.ui.common.MarkdownParser
import com.example.mikeyllmapplication.ui.common.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun parsesHeadingsBulletsParagraphsAndCode() {
        val md = """
            ## Wi-Fi
            Your signal is **excellent**.
            Second line.

            - RSSI: -50 dBm
              - nested
            1. first
            ```
            code here
            ```
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertEquals(MdBlock.Heading(2, "Wi-Fi"), blocks[0])
        assertEquals(MdBlock.Paragraph("Your signal is **excellent**.\nSecond line."), blocks[1])
        assertEquals(MdBlock.Bullet(0, "•", "RSSI: -50 dBm"), blocks[2])
        assertEquals(MdBlock.Bullet(1, "•", "nested"), blocks[3])
        assertEquals(MdBlock.Bullet(0, "1.", "first"), blocks[4])
        assertEquals(MdBlock.Code("code here"), blocks[5])
    }

    @Test
    fun unterminatedCodeBlockDuringStreamingIsKept() {
        val blocks = MarkdownParser.parse("text\n```\npartial")
        assertEquals(MdBlock.Code("partial"), blocks.last())
    }

    @Test
    fun inlineStylesStripMarkers() {
        val s = MarkdownParser.inline("a **b** `c` *d* e*", Color.Gray)
        assertEquals("a b c d e*", s.text)
        assertEquals(3, s.spanStyles.size)
    }

    @Test
    fun unclosedBoldIsLiteral() {
        assertEquals("**bold", MarkdownParser.inline("**bold", Color.Gray).text)
    }
}

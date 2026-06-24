package com.elcruncharino.neostego.compose.engine

import com.openstego.desktop.util.PluginManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

class EngineTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun loadPlugins() = PluginManager.loadPlugins()
    }

    private fun makeCover(file: File, w: Int = 96, h: Int = 96) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (x * 7 + y * 13) and 0xFFFFFF)
        ImageIO.write(img, "png", file)
    }

    @Test
    fun `hide then extract round-trips with encryption`(@TempDir tmp: Path) {
        val cover = tmp.resolve("cover.png").toFile().also { makeCover(it) }
        val msg = tmp.resolve("msg.txt").toFile().apply { writeText("hello secret world") }
        val out = tmp.resolve("out.png").toString()
        embed(EmbedRequest("RandomLSB", msg.path, cover.path, out, "AES128", "p@ss"))
        val outDir = tmp.resolve("extract").toFile().apply { mkdirs() }
        val extracted = extract(out, "p@ss", outDir.path)
        assertEquals("hello secret world", File(extracted).readText())
    }

    @Test
    fun `encryption without a password fails clearly`(@TempDir tmp: Path) {
        val cover = tmp.resolve("c.png").toFile().also { makeCover(it) }
        val msg = tmp.resolve("m.txt").toFile().apply { writeText("x") }
        val out = tmp.resolve("o.png").toString()
        val ex = assertThrows<IllegalArgumentException> {
            embed(EmbedRequest("RandomLSB", msg.path, cover.path, out, "AES128", ""))
        }
        assertTrue(ex.message!!.contains("password"))
    }

    @Test
    fun `data hiding algorithms expose the right options kinds`() {
        val algos = dataHidingAlgorithms()
        assertTrue(algos.any { it.name == "RandomLSB" && it.optionsKind == OptionsKind.LSB })
        assertTrue(algos.any { it.name == "Adaptive" && it.optionsKind == OptionsKind.ADAPTIVE })
        assertTrue(algos.any { it.name == "JpegUniward" && it.optionsKind == OptionsKind.JPEG })
        assertTrue(algos.any { it.name == "WavLSB" && it.optionsKind == OptionsKind.NONE })
    }

    @Test
    fun `capacity reflects the cover size`(@TempDir tmp: Path) {
        val cover = tmp.resolve("cap.png").toFile().also { makeCover(it, 100, 100) }
        val cap = coverCapacityBytes("RandomLSB", cover.path, AdvancedOptions())
        // ~100*100*3/8 minus header, so clearly positive and in the right ballpark.
        assertTrue(cap != null && cap in 1..4000)
    }
}

package com.elcruncharino.neostego.compose.engine

import com.openstego.desktop.util.PluginManager
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
    fun `split across covers round-trips (and really splits)`(@TempDir tmp: Path) {
        // Size the payload to 1.5x one cover's measured capacity: too big for a single cover, so it
        // must span both parts, but small enough that the two covers together hold it.
        val c1 = tmp.resolve("c1.png").toFile().also { makeCover(it, 80, 80) }
        val c2 = tmp.resolve("c2.png").toFile().also { makeCover(it, 80, 80) }
        val capOne = coverCapacityBytes("RandomLSB", c1.path, AdvancedOptions())!!
        // Incompressible random bytes so compression can't shrink it back under one cover.
        val payload = ByteArray((capOne * 3 / 2).toInt()).also { java.util.Random(42).nextBytes(it) }
        val msg = tmp.resolve("secret.bin").toFile().apply { writeBytes(payload) }
        val outDir = tmp.resolve("out").toFile().apply { mkdirs() }

        val parts = embedSplitCovers("RandomLSB", msg.path, listOf(c1.path, c2.path), outDir.path, "AES128", "pw")
        assertEquals(2, parts.size)
        assertTrue(parts.all { File(it).isFile && File(it).length() > 0 })

        val reDir = tmp.resolve("re").toFile().apply { mkdirs() }
        val restored = extractSplitFiles(parts, "pw", reDir.path)
        assertArrayEquals(payload, File(restored).readBytes())
    }

    @Test
    fun `batch embeds the same message into every cover`(@TempDir tmp: Path) {
        val c1 = tmp.resolve("a.png").toFile().also { makeCover(it) }
        val c2 = tmp.resolve("b.png").toFile().also { makeCover(it) }
        val msg = tmp.resolve("m.txt").toFile().apply { writeText("same secret") }
        val outDir = tmp.resolve("batch").toFile().apply { mkdirs() }

        val outs = embedBatch("RandomLSB", msg.path, listOf(c1.path, c2.path), outDir.path, null, "pw")
        assertEquals(2, outs.size)
        outs.forEachIndexed { i, o ->
            val exDir = tmp.resolve("ex$i").toFile().apply { mkdirs() }
            assertEquals("same secret", File(extract(o, "pw", exDir.path)).readText())
        }
    }

    @Test
    fun `random image cover needs no cover file and round-trips`(@TempDir tmp: Path) {
        val msg = tmp.resolve("m.txt").toFile().apply { writeText("hidden in noise") }
        val out = tmp.resolve("rand.png").toString()
        embed(EmbedRequest("RandomLSB", msg.path, "", out, "AES128", "pw", useRandomImage = true))
        assertTrue(File(out).isFile)
        val exDir = tmp.resolve("ex").toFile().apply { mkdirs() }
        assertEquals("hidden in noise", File(extract(out, "pw", exDir.path)).readText())
    }

    @Test
    fun `capacity reflects the cover size`(@TempDir tmp: Path) {
        val cover = tmp.resolve("cap.png").toFile().also { makeCover(it, 100, 100) }
        val cap = coverCapacityBytes("RandomLSB", cover.path, AdvancedOptions())
        // ~100*100*3/8 minus header, so clearly positive and in the right ballpark.
        assertTrue(cap != null && cap in 1..4000)
    }
}

package app.qitou.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundRestrictionGuideTest {
    @Test
    fun usesColorOsPathForOppoFamily() {
        val guide = BackgroundRestrictionGuide.forManufacturer("OnePlus")

        assertEquals("ColorOS 后台行为", guide.title)
        assertEquals(listOf("应用信息", "耗电管理", "完全允许后台行为"), guide.path)
    }

    @Test
    fun usesMagicOsPathForHonor() {
        val guide = BackgroundRestrictionGuide.forManufacturer("HONOR")

        assertEquals("MagicOS 后台行为", guide.title)
        assertEquals(listOf("应用信息", "电池", "允许后台活动"), guide.path)
    }

    @Test
    fun usesOriginOsPathForVivoFamily() {
        val guide = BackgroundRestrictionGuide.forManufacturer("vivo")

        assertEquals("OriginOS 后台行为", guide.title)
        assertEquals(listOf("应用信息", "电池", "后台耗电管理"), guide.path)
    }

    @Test
    fun usesGenericPathForUnknownManufacturer() {
        val guide = BackgroundRestrictionGuide.forManufacturer("Example")

        assertEquals("系统后台限制", guide.title)
        assertEquals(listOf("应用信息", "电池/后台", "允许后台运行"), guide.path)
    }
}

package com.example.voiceclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 校验提示音配置的默认值与枚举解析。 */
class ChimeSettingsTest {

    @Test fun defaultExtrasUsesBuiltInChime() {
        val extras = Extras()
        assertEquals(ChimeSource.DEFAULT, extras.chimeSource)
        assertEquals("", extras.chimeUri)
        assertEquals("", extras.chimeLabel)
    }

    @Test fun chimeSourceHasThreeModes() {
        val names = ChimeSource.values().map { it.name }
        assertEquals(listOf("DEFAULT", "SYSTEM", "SILENT"), names)
    }

    @Test fun chimeSourceParseFallsBackToDefault() {
        // 模拟旧数据/脏数据：无法解析时回退 DEFAULT
        val parsed = runCatching { ChimeSource.valueOf("NOT_A_MODE") }.getOrDefault(ChimeSource.DEFAULT)
        assertEquals(ChimeSource.DEFAULT, parsed)
    }

    @Test fun copyPreservesChimeFields() {
        val extras = Extras(chimeSource = ChimeSource.SYSTEM, chimeUri = "content://x", chimeLabel = "铃声A")
        val copied = extras.copy(duckOthers = false)
        assertEquals(ChimeSource.SYSTEM, copied.chimeSource)
        assertEquals("content://x", copied.chimeUri)
        assertEquals("铃声A", copied.chimeLabel)
        assertTrue(!copied.duckOthers)
    }
}

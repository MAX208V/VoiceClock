package com.example.voiceclock

import org.junit.Test
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 临时诊断：把算法输出的 24 节气日期写入固定格式，便于比对。 */
class DiagnosticSolarTermsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    @Test fun dumpAllTerms() {
        val sb = StringBuilder()
        for (year in listOf(2024, 2025, 2026, 2027, 2028)) {
            sb.append("YEAR ").append(year).append('\n')
            for ((name, time) in SolarTerms.termsOfYear(year, zone)) {
                sb.append("  ").append(name).append(' ').append(time.format(fmt)).append('\n')
            }
        }
        val out = java.io.File("solar-terms-dump.txt")
        out.writeText(sb.toString())
    }
}

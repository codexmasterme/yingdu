package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotifyTest {
    @Test fun whichNotificationsAreLogged() {
        // 外卖、打车 app：全部记
        assertTrue(LiveNotify.wanted("me.ele", ongoing = false, promoted = false, extraKeys = emptyList()))
        assertTrue(LiveNotify.wanted("com.sdu.didi.psnger", ongoing = true, promoted = false, extraKeys = emptyList()))
        // 别的 app：只记带流体云 / 实况字样字段、或者实况（推广）的进行中通知
        assertTrue(LiveNotify.wanted("com.example", ongoing = true, promoted = false, extraKeys = listOf("android.title", "oplus.fluidcloud.data")))
        assertTrue(LiveNotify.wanted("com.example", ongoing = true, promoted = true, extraKeys = emptyList()))
        assertFalse(LiveNotify.wanted("com.example", ongoing = true, promoted = false, extraKeys = listOf("android.title", "android.progress")))
        assertFalse(LiveNotify.wanted("com.example", ongoing = false, promoted = false, extraKeys = listOf("oplus.x")))
        // 别的 app 的前台服务（VPN 每秒刷新网速）：ColorOS 加的 oplus_ 字段不算数
        assertFalse(LiveNotify.wanted("com.niubi.app", ongoing = true, promoted = false, extraKeys = listOf("oplus_small_icon"), foreground = true))
        assertTrue(LiveNotify.wanted("me.ele", ongoing = true, promoted = false, extraKeys = emptyList(), foreground = true))
        // 别的 app 同一条通知 10 分钟只记一次；外卖打车 app 每次都记
        assertTrue(LiveNotify.due("com.x", "kx", 0)); assertFalse(LiveNotify.due("com.x", "kx", 60_000)); assertTrue(LiveNotify.due("com.x", "kx", 11 * 60_000))
        assertTrue(LiveNotify.due("me.ele", "ke", 0)); assertTrue(LiveNotify.due("me.ele", "ke", 1))
    }

    @Test fun formatAndDedup() {
        val t = LiveNotify.format("10-01 12:00:00", "更新", "me.ele", listOf("extras.android.title" to "骑手正在赶往商家", "空的" to "", "多行" to "a\nb"))
        assertTrue(t.startsWith("=== 10-01 12:00:00 更新 饿了么 (me.ele)\n"))
        assertTrue(t.contains("  extras.android.title: 骑手正在赶往商家\n"))
        assertFalse(t.contains("空的"))
        assertTrue(t.contains("多行: a⏎b"))
        assertTrue(LiveNotify.changed("k1", "A")); assertFalse(LiveNotify.changed("k1", "A")); assertTrue(LiveNotify.changed("k1", "B"))
        assertTrue(LiveNotify.forget("k1")); assertFalse(LiveNotify.forget("k1"))
        assertEquals("滴滴出行", LiveNotify.APPS["com.sdu.didi.psnger"])
    }
}

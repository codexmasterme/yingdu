// 连接流程参考 MentraOS 旧版 Nimo 驱动（Apache License 2.0，提交 2c91f97be）。
package io.github.yingdu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper

/** 眼镜镜腿/头部动作。 */
/** HOME_WAKE：在官方主界面上抬头（眼镜发来起因为「抬头」的看板拉取，key 6）。 */
enum class GlassesInput { CLICK_RIGHT, CLICK_LEFT, DOUBLE_RIGHT, DOUBLE_LEFT, LONG_PRESS, HEAD_UP, HEAD_DOWN, EXITED, HOME_WAKE }

enum class LinkState { DISCONNECTED, CONNECTING, HANDSHAKING, READY }

/** 文字显示在哪个页面。 */
enum class DisplayPage { PROMPTER, NOTE }

interface NimoListener {
    /** 眼镜当前的开关设置（抬头显示、息屏模式、自动亮度），连接后读取、修改后回报。 */
    fun onGlassesSetting(key: Int, on: Boolean) {}
    /** 眼镜现在的亮度档位（0..16，连接时读到的）。 */
    fun onBrightnessLevel(level: Int) {}
    /** 眼镜上报的自动亮度档位（0..16）。 */
    fun onAutoBrightnessLevel(level: Int) {}
    fun onLinkState(state: LinkState)
    fun onInput(input: GlassesInput)
    fun onBattery(level: Int, charging: Boolean)
    fun onFirmware(version: String)
    /**
     * 两条镜腿之间的连接（TWS）断开后又连上了。手机只连着一条镜腿，另一条靠 TWS 同步画面；
     * 重新连上的那一边不知道现在开着什么页面，要重新打开页面、补发画面，否则只有半边显示。
     */
    fun onTwsReconnected() {}
    fun onLog(msg: String)
    /** 眼镜麦克风的音频帧（开麦后才有）。 */
    fun onMicFrames(frames: List<NimoMic.Frame>) {}
}

/**
 * 旧固件（无动态画布）下的最小 Nimo 客户端。
 * 连接要点（来自 MentraOS 驱动的注释）：
 *  1. 眼镜要先在系统里完成经典蓝牙配对（原厂 app 配对过就行）；
 *  2. 数据通道是跑在 BR/EDR 上的 GATT 服务 0x7033，所以用 TRANSPORT_BREDR 连接；
 *  3. 连上后等眼镜报告 TWS（两条镜腿）连接状态，再同步时间，收到 ACK 才算握手完成；
 *  4. 文字显示在眼镜自带的"语音笔记"页面里。
 * 所有状态只在主线程上读写。
 */
@SuppressLint("MissingPermission")
class NimoClient(private val context: Context, private val listener: NimoListener) {

    /** 只发亮度档位（0..16；自动亮度平滑过渡时一步一步发）。 */
    fun setBrightnessLevel(level: Int) {
        if (state != LinkState.READY) return
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_BRIGHTNESS,
            byteArrayOf(level.coerceIn(0, NimoProtocol.MAX_BRIGHTNESS_LEVEL).toByte())))
    }

    companion object {
        /** 百分比 → 0..16 档（官方是直接舍去小数）。 */
        fun brightnessLevel(percent: Int): Int = (percent.coerceIn(0, 100) / 100.0 * NimoProtocol.MAX_BRIGHTNESS_LEVEL).toInt()
        private const val TWS_TIMEOUT_MS = 10_000L
        private const val ACK_TIMEOUT_MS = 5_000L
        /** 麦克风特征值的属性句柄（实测）。 */
        private const val MIC_HANDLE = 3
        private const val KEEPALIVE_MS = 30_000L
        private const val WRITE_WATCHDOG_MS = 1_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        /** 前这么多次每 5 秒重连一次（一次失败本身要等二三十秒，大约 10 分钟），之后每分钟一次。 */
        private const val RECONNECT_FAST_TRIES = 20
        private const val RECONNECT_SLOW_MS = 60_000L
        private const val SET_TIME_MAX_ATTEMPTS = 3
        /** 打开页面后在这些时间点先发图（不等"已打开"的确认）。 */
        private val EARLY_IMAGE_MS = longArrayOf(100, 500, 900)
        /** 刚点亮主界面就打开页面时，在这些时间点再发一次"打开页面"（稍早于上面发图的时间点）。 */
        private val ENTER_RETRY_MS = longArrayOf(300, 600, 900, 1200, 1500)
        /** 打开页面后等多久没收到确认就当作已打开，正式发最后一次。 */
        private const val NAV_ENTER_WAIT_MS = 1_300L
        /** 关屏退回主界面后多久松开关屏（官方主界面约 10 秒自己熄灭）。 */
        private const val DARK_HOME_RELEASE_MS = 12_000L
        /** 回复看板拉取时告诉眼镜多少秒后再定时来拉（官方默认 60；只有抬头那次拉取才叫回看板，定时的不用）。 */
        private const val DASH_PULL_INTERVAL_SEC = 60
        /**
         * 开屏后最多等眼镜确认多久再弹通知。确认一般 0.1 秒左右就回来，但蓝牙在省电状态时要 1 秒多
         * （v4.9.7 日志：等了 0.8 秒没确认就发，弹窗赶在屏幕亮起前被丢掉），所以多等一会儿。
         */
        const val SCREEN_ON_WAIT_MS = 3_000L

        /**
         * 提词器页面一共 7 行：第 1 行是状态栏（时间、章节），第 2 行空着，下面 5 行在框里。
         * 正文前加两个换行，让正文从框内第一行开始（实测）。
         */
        const val PROMPTER_TOP_PADDING = "\n\n"

        fun isNimoName(name: String?): Boolean {
            val lower = name?.lowercase() ?: return false
            return lower.startsWith(NimoBLE.NAME_PREFIX) && !lower.endsWith(NimoBLE.BLE_NAME_SUFFIX)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val assembler = NimoReceiveAssembler()

    var state = LinkState.DISCONNECTED
        private set
    private var wantConnected = false
    private var twsConnected = false
    /** 我们当前打开的眼镜页面（appId），null 表示没有。 */
    private var enteredApp: Int? = null
    /** 正在等待眼镜确认打开的页面。 */
    private var enteringApp: Int? = null
    private var enterTimeout: Runnable? = null
    private var session = (System.currentTimeMillis() / 1000 % 10_000).toInt()
    private val random = java.security.SecureRandom()

    /**
     * 图片显示在导航页（appId 1）的大图区域：试过语音笔记、AI 对话、提词器页都不显示图片；
     * 眼镜没有别的原生画布，所以在手机上画好图再推给眼镜。
     */
    private val imageApp = NimoProtocol.APP_ID_NAV
    private val imageRes = NimoProtocol.NAV_RES_LARGE_MAP

    /** 文字显示在哪个页面；切换后下一次显示会自动进入新页面。 */
    var page = DisplayPage.PROMPTER

    private val pageAppId get() =
        if (page == DisplayPage.PROMPTER) NimoProtocol.APP_ID_PROMPTER else NimoProtocol.APP_ID_ASR_NOTE

    /** 待发送的内容：一页文字（可带提词器状态行）或一张图片。只保留最新的一份。 */
    private sealed class Content {
        class Text(val text: String, val status: String?) : Content()
        class Image(val packed2bpp: ByteArray, val width: Int, val height: Int) : Content()
    }
    private var pending: Content? = null
    private var flushWhenIdle = false
    private var setTimeAttempts = 0

    private val writeQueue = ArrayDeque<ByteArray>()
    /**
     * 写别的特征值 / 描述符（麦克风的开关、打开通知）：和发数据共用"同一时刻只能一个 GATT 操作"的顺序，优先执行。
     * 返回 true 表示已经发起、要等回调；false 表示没发起（跳过）。
     */
    private val gattOps = ArrayDeque<() -> Boolean>()
    /** 麦克风的特征值（属性句柄 3，实测；UUID 在连上后的日志里）。 */
    private var mic: BluetoothGattCharacteristic? = null
    private var writeInFlight = false
    private var watchdog: Runnable? = null

    private class PendingAck(val onResult: (Boolean) -> Unit, val timeout: Runnable)
    private val pendingAcks = mutableMapOf<Int, PendingAck>()


    // ---------- 对外接口 ----------

    fun connect(target: BluetoothDevice) {
        wantConnected = true
        failedAttempts = 0
        device = target
        openGatt()
    }

    fun disconnect() {
        wantConnected = false
        main.removeCallbacksAndMessages(null)
        if (state == LinkState.READY && (blanked || darkHome)) {
            blanked = false; darkHome = false
            writeRaw(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(0), needsAck = false))
            reportDisplayOff(false)
        }
        val app = enteredApp
        if (state == LinkState.READY && app != null) {
            // 退出我们打开的页面，眼镜回到原来的界面（尽力而为，不等回应）
            writeRaw(NimoFrameCodec.encodeFrame(
                NimoProtocol.CMD_CONTROL_INSTRUCTION, NimoProtocol.CTRL_QUIT_APP,
                byteArrayOf(app.toByte()), needsAck = false))
        }
        val g = gatt
        gatt = null
        resetSession()
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
        setState(LinkState.DISCONNECTED)
    }

    /** 在眼镜上显示一整页文字（覆盖上一页）。未就绪时暂存，握手完成后自动发出。 */
    fun showText(text: String, status: String? = null) {
        pending = Content.Text(text.ifEmpty { " " }, status)
        if (state == LinkState.READY) flush()
    }

    /**
     * 在导航页的大图区域显示一张 2bpp 图片（452×170）。
     * 格式来自 MentraOS 驱动，实机验证过。
     */
    fun showImage(packed2bpp: ByteArray, width: Int, height: Int) {
        pending = Content.Image(packed2bpp, width, height)
        if (state == LinkState.READY) flush()
    }

    /**
     * 眼镜的通知弹窗（固件自带）：cmd=0x09 key=0x01，固定 319 字节：
     * 图标类型(1) + 标题(51 字节 UTF-8) + 时间(9) + 正文长度(2) + 正文(256 字节 UTF-8)。格式来自 MentraOS 驱动。
     */
    /** 现在开着的页面（null = 在官方主界面）。 */
    fun enteredAppId(): Int? = enteredApp

    /** 发过的通知弹窗、开屏请求（测试、排查用）。 */
    var popupsSent = 0; private set
    var screenOnRequests = 0; private set

    /**
     * @param appId 第一个字节 notificationAppId：眼镜按它显示是哪个 app（图标和名字），0 是短信、16 是微信，
     *   编号表见 NotifyIcons；不认识的 app 发 OTHER。
     */
    fun sendNotification(title: String, content: String, appId: Int = NotifyIcons.OTHER) {
        if (state != LinkState.READY) return
        lightHome()          // 关着屏时眼镜自带的弹窗看不到
        popupsSent++
        val payload = ByteArray(319)
        payload[0] = appId.coerceIn(0, 255).toByte()
        utf8Into(payload, 1, title, 51)
        NimoFrameCodec.encodeDeviceTime().copyInto(payload, 52)
        val clen = utf8Into(payload, 63, content, 256)
        payload[61] = (clen and 0xFF).toByte()
        payload[62] = ((clen shr 8) and 0xFF).toByte()
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_CONTROL_NOTIFICATION, NimoProtocol.NOTIFICATION_SEND, payload))
    }

    /** 写入 UTF-8，超长时在字符边界截断；返回写入的字节数。 */
    private fun utf8Into(dest: ByteArray, offset: Int, text: String, maxLen: Int): Int {
        var bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxLen) {
            var end = maxLen
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            bytes = bytes.copyOfRange(0, end)
        }
        bytes.copyInto(dest, offset)
        return bytes.size
    }

    /** 退出我们打开的眼镜页面，眼镜回到官方主界面（"收起显示"）。 */
    fun quitCurrent() {
        pending = null
        val app = enteredApp ?: return
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_CONTROL_INSTRUCTION, NimoProtocol.CTRL_QUIT_APP, byteArrayOf(app.toByte())))
        enteredApp = null
    }

    /**
     * 萤读退回主界面时顺手把屏幕关了（低着头收起看板：眼镜自己只在抬头→低头那一下变暗，退出时已经低着头，主界面会一直亮着）。
     * 之后打开任何页面时等内容画好再开屏；弹通知、叫亮主界面、断线重连时也会开屏。
     */
    var darkHome = false; private set

    /** 关屏，再退出当前页面（主界面在黑屏底下画好，不会亮）。 */
    fun quitToDarkHome() {
        if (state != LinkState.READY) return
        pending = null
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(1)))
        reportDisplayOff(true)
        enteredApp?.let { app ->
            sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_CONTROL_INSTRUCTION, NimoProtocol.CTRL_QUIT_APP, byteArrayOf(app.toByte())))
        }
        enteredApp = null
        darkHome = true
        main.removeCallbacks(releaseDark); main.postDelayed(releaseDark, DARK_HOME_RELEASE_MS)
    }

    /**
     * 关屏退回主界面后，过一会儿（官方主界面这时自己已经熄灭了）松开关屏，交回眼镜自己按抬头低头开关屏。
     * 这期间已经因为别的原因开过屏（抬头叫回看板、来通知……）就不用了。
     */
    private val releaseDark = Runnable {
        if (!darkHome || state != LinkState.READY) return@Runnable
        darkHome = false
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(0)))
        reportDisplayOff(false)
        log("关屏退回主界面 ${DARK_HOME_RELEASE_MS / 1000} 秒了：松开关屏，交回眼镜自己按抬头低头开关屏【测试】")
    }

    /** 关着屏停在主界面时把屏幕打开（显示官方主界面）。 */
    fun lightHome() {
        if (!darkHome) return
        darkHome = false
        if (state == LinkState.READY) { sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(0))); reportDisplayOff(false) }
    }

    /** 萤读自己开关了息屏（关屏收起、关屏回主界面、松开关屏……）：告诉界面，首页的「息屏」按钮跟着眼镜的实际状态走。 */
    private fun reportDisplayOff(off: Boolean) { listener.onGlassesSetting(NimoProtocol.SET_DISPLAY_OFF, off) }

    /** 临时关屏 / 开屏（萤读自己的抬头显示用），和用户的"息屏模式"开关分开记。 */
    fun screenOff(off: Boolean) {
        if (state != LinkState.READY) return
        if (!off) darkHome = false
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(if (off) 1 else 0)))
        reportDisplayOff(off)
    }

    /**
     * 抬头叫回看板时开屏：插到发送队列最前面（不排在还没发完的图后面），并记下用了多久：
     * 从收到"抬头"到指令写出去、到眼镜确认，各多少毫秒（排查"抬头后两三秒才亮"用）。
     */
    fun screenOnUrgent() {
        if (state != LinkState.READY || tx == null) return
        darkHome = false
        val headAt = lastHeadAt
        val t0 = android.os.SystemClock.uptimeMillis()
        val ackKey = (NimoProtocol.CMD_SET_PARAMETER shl 8) or NimoProtocol.SET_DISPLAY_OFF
        pendingAcks.remove(ackKey)?.let { main.removeCallbacks(it.timeout); it.onResult(false) }
        val timeout = Runnable { pendingAcks.remove(ackKey)?.onResult?.invoke(false) }
        pendingAcks[ackKey] = PendingAck({ ok ->
            val now = android.os.SystemClock.uptimeMillis()
            val sinceHead = if (headAt > 0 && t0 - headAt < 5_000) "，从收到抬头算 ${now - headAt} 毫秒" else ""
            log(if (ok) "开屏确认：发出后 ${now - t0} 毫秒$sinceHead" else "开屏没收到确认（${now - t0} 毫秒）")
        }, timeout)
        main.postDelayed(timeout, ACK_TIMEOUT_MS)
        writeQueue.addFirst(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(0), needsAck = true))
        reportDisplayOff(false)
        drainWrites()
    }

    /**
     * 开屏，等眼镜确认屏幕真的开了再执行 then（最多等 SCREEN_ON_WAIT_MS）。关屏时发通知眼镜不弹，
     * 开屏后马上发也可能赶在屏幕亮起之前被丢掉（v3.7 实测时好时坏）。
     */
    fun screenOnThen(then: () -> Unit) {
        if (state != LinkState.READY) return
        screenOnRequests++
        var done = false
        val go = Runnable { if (!done) { done = true; then() } }
        reportDisplayOff(false)
        sendAwaitingAck(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(0)) { ok ->
            if (!done) log(if (ok) "眼镜确认已开屏" else "眼镜拒绝开屏")
            main.post(go)
        }
        main.postDelayed({ if (!done) { log("开屏 ${SCREEN_ON_WAIT_MS / 1000} 秒没等到确认，直接发"); go.run() } }, SCREEN_ON_WAIT_MS)
    }

    /**
     * 临时改"抬头显示"（关屏时弹通知用），不回报给界面：界面上仍是用户自己的设置。
     * 抬头显示开着时眼镜自己按头的角度开关屏，低着头时我们发"开屏"会被它压回去（通知大多弹不出来的原因）。
     */
    fun headUpDisplayTemp(on: Boolean) {
        if (state != LinkState.READY) return
        sendAwaitingAck(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_HEADUP_DISPLAY, byteArrayOf(if (on) 1 else 0)) { ok ->
            log((if (on) "恢复抬头显示" else "临时关掉抬头显示") + if (ok) "" else "（眼镜没确认）")
        }
    }

    /** 设置眼镜的开关项（抬头显示 0x0D、息屏模式 0x0F、自动亮度 0x0E），成功后回报新状态。 */
    fun setGlassesSetting(key: Int, on: Boolean) {
        if (state != LinkState.READY) return
        // 手动关掉息屏：萤读自己的关屏回主界面也一起结束（不再等 12 秒后松开）
        if (key == NimoProtocol.SET_DISPLAY_OFF && !on) { darkHome = false; main.removeCallbacks(releaseDark) }
        sendAwaitingAck(NimoProtocol.CMD_SET_PARAMETER, key, byteArrayOf(if (on) 1 else 0)) { ok ->
            if (ok) listener.onGlassesSetting(key, on) else log("眼镜没有接受设置 key=$key")
        }
    }

    /**
     * 调亮度（和官方 app 一样）：总是发亮度等级（0..16 档）；自动亮度开着时再发一条亮度偏移
     * （新档位 − 原来的档位），自动亮度保持开着，眼镜在自动调节的基础上整体调亮 / 调暗。
     */
    fun setBrightness(percent: Int, previousPercent: Int, autoOn: Boolean) {
        if (state != LinkState.READY) return
        val lvl = brightnessLevel(percent)
        setBrightnessLevel(lvl)
        if (autoOn) {
            val offset = (lvl - brightnessLevel(previousPercent)).coerceIn(-128, 127)
            sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_BRIGHTNESS_OFFSET, byteArrayOf(offset.toByte())))
            log("自动亮度开着：亮度偏移 $offset（新 $lvl 档）")
        }
    }


    // ---------- 连接 ----------

    private fun openGatt() {
        val d = device ?: return
        if (d.bondState != BluetoothDevice.BOND_BONDED) {
            log("设备未配对。请先在原厂 app 或系统蓝牙设置里配对眼镜")
            setState(LinkState.DISCONNECTED)
            return
        }
        setState(LinkState.CONNECTING)
        log("正在连接 ${d.name ?: d.address}（BR/EDR）")
        gatt = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_BREDR)
        if (gatt == null) {
            log("connectGatt 返回空")
            setState(LinkState.DISCONNECTED)
            scheduleReconnect()
        }
    }

    /** 连续连不上的次数（连上后清零）：眼镜不在身边时别一直每 5 秒找一次，改成每分钟。 */
    private var failedAttempts = 0
    private val reconnectRun = Runnable { if (wantConnected && gatt == null) openGatt() }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        failedAttempts++
        val delay = if (failedAttempts <= RECONNECT_FAST_TRIES) RECONNECT_DELAY_MS else RECONNECT_SLOW_MS
        log(if (failedAttempts == RECONNECT_FAST_TRIES + 1) "一直连不上，改成每 ${RECONNECT_SLOW_MS / 1000} 秒试一次（打开萤读会马上重试）"
            else "${delay / 1000} 秒后重连")
        main.removeCallbacks(reconnectRun)
        main.postDelayed(reconnectRun, delay)
    }

    /** 打开萤读时：如果正在慢慢重试，马上试一次。 */
    fun reconnectNow() {
        if (!wantConnected || gatt != null || state != LinkState.DISCONNECTED) return
        if (failedAttempts <= RECONNECT_FAST_TRIES) return
        failedAttempts = RECONNECT_FAST_TRIES     // 这次再失败，还是慢慢试
        main.removeCallbacks(reconnectRun)
        log("打开了萤读：马上重连一次")
        openGatt()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("已连上，发现服务中")
                    // 不主动申请大 MTU：部分 Nimo 固件会因此报错并反复重连
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("连接断开 status=$status")
                    try { g.close() } catch (_: Exception) {}
                    gatt = null
                    resetSession()
                    setState(LinkState.DISCONNECTED)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                val service = g.getService(NimoBLE.SERVICE_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                    log("没找到 Nimo 数据服务（status=$status），断开重试")
                    g.disconnect()
                    return@post
                }
                tx = service.getCharacteristic(NimoBLE.CHAR_TX)
                rx = service.getCharacteristic(NimoBLE.CHAR_RX)
                val r = rx
                if (tx == null || r == null) {
                    log("缺少收发特征值，断开重试")
                    g.disconnect()
                    return@post
                }
                // 服务发现后请求高连接优先级（传图更快）
                val prio = try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) { false }
                log(if (prio) "已请求高连接优先级" else "请求高连接优先级失败（不影响使用）")
                findMic(g)
                enableNotifications(g, r)
            }
        }

        @Deprecated("API 33 以下使用")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = c.value ?: return
            if (c.uuid == NimoBLE.CHAR_RX) main.post { handleRx(data) }
            else if (c === mic || c.instanceId == MIC_HANDLE) main.post { onMicData(data) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == NimoBLE.CHAR_RX) main.post { handleRx(value) }
            else if (c === mic || c.instanceId == MIC_HANDLE) main.post { onMicData(value) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.postDelayed({ writeInFlight = false; drainWrites() }, NimoBLE.INTER_FRAME_DELAY_MS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g != gatt) return@post
                // 收数据通道的通知打开了：开始握手；其他描述符（麦克风）是写队列里的操作，接着发下一条
                if (d.characteristic.uuid == NimoBLE.CHAR_RX) startHandshake()
                else { if (status != BluetoothGatt.GATT_SUCCESS) log("写描述符失败 status=$status"); writeInFlight = false; drainWrites() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(NimoBLE.CCCD)
        if (d == null) { startHandshake(); return }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    // ---------- 握手 ----------

    private fun startHandshake() {
        if (state == LinkState.HANDSHAKING || state == LinkState.READY) return
        setState(LinkState.HANDSHAKING)
        log("握手：等待镜腿连接状态")
        if (twsConnected) { syncTime(); return }
        main.postDelayed({
            if (state == LinkState.HANDSHAKING && !twsConnected) {
                log("等待镜腿状态超时，断开重试（两条镜腿都开机了吗？）")
                gatt?.disconnect()
            }
        }, TWS_TIMEOUT_MS)
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_GET_PARAMETER, NimoProtocol.GET_TWS_STATUS))
    }

    private fun onTws(connected: Boolean) {
        val wasConnected = twsConnected
        twsConnected = connected
        if (connected && !wasConnected && state == LinkState.HANDSHAKING) {
            setTimeAttempts = 0
            syncTime()
        } else if (state == LinkState.READY && connected != wasConnected) {
            main.removeCallbacks(twsResync)
            if (connected) {
                log("两条镜腿重新连上了：稍后重新同步画面")
                main.postDelayed(twsResync, 1_500)     // 等连接稳定（来回断连时只同步最后一次）
            } else log("两条镜腿之间断开了（这时只有一边能显示）")
        }
    }

    /** 重新给眼镜校时（手机改了时间 / 换了时区、每天一次）。 */
    fun resyncTime(why: String) {
        if (state != LinkState.READY) return
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_TIME, NimoFrameCodec.encodeDeviceTime()))
        log("重新给眼镜校时（$why）")
    }

    /** TWS 重新连上后：重新校时，下一份内容先把页面重开一次，交给上层补发当前画面。 */
    private val twsResync = Runnable {
        if (state != LinkState.READY || !twsConnected) return@Runnable
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_TIME, NimoFrameCodec.encodeDeviceTime()))
        reopenBeforeNextContent()
        listener.onTwsReconnected()
    }

    private fun syncTime() {
        if (state != LinkState.HANDSHAKING || gatt == null) return
        setTimeAttempts++
        sendAwaitingAck(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_TIME, NimoFrameCodec.encodeDeviceTime()) { ok ->
            when {
                ok -> finishHandshake()
                setTimeAttempts < SET_TIME_MAX_ATTEMPTS -> main.postDelayed({ syncTime() }, 500)
                else -> { log("时间同步失败，断开重试"); gatt?.disconnect() }
            }
        }
    }

    private fun finishHandshake() {
        if (state != LinkState.HANDSHAKING) return
        log("握手完成")
        failedAttempts = 0
        sendFrame(NimoFrameCodec.encodeFrame(
            NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_PHONE_TYPE,
            byteArrayOf(NimoProtocol.PHONE_TYPE_OTHER.toByte()), needsAck = false))
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_GET_PARAMETER, NimoProtocol.GET_VERSION))
        for (k in listOf(NimoProtocol.SET_HEADUP_DISPLAY, NimoProtocol.SET_DISPLAY_OFF, NimoProtocol.SET_AUTO_BRIGHTNESS, NimoProtocol.SET_BRIGHTNESS))
            sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_GET_PARAMETER, k))
        pollBattery()
        if (needUnblankOnConnect) {
            needUnblankOnConnect = false
            sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(0)))
            reportDisplayOff(false)
        }
        setState(LinkState.READY)
        flushText()
    }

    /** 每 30 秒查一次电量，兼作保活，避免系统回收空闲连接。 */
    private fun pollBattery() {
        if (state != LinkState.READY && state != LinkState.HANDSHAKING) return
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_GET_PARAMETER, NimoProtocol.GET_BATTERY))
        main.postDelayed({ if (state == LinkState.READY) pollBattery() }, KEEPALIVE_MS)
    }

    // ---------- 显示文字 ----------

    private fun flushText() = flush()

    private fun flush() {
        val c = pending ?: return
        // 上一屏的数据还在发送队列里：先不发，等队列清空后只发最新的一份（翻译、自动滚动时避免越积越多）
        if (writeQueue.size > 2 || writeInFlight && writeQueue.isNotEmpty()) { flushWhenIdle = true; return }
        val target = if (c is Content.Image) imageApp else pageAppId
        val reopen = reopenNext && enteringApp == null && enteredApp == target
        reopenNext = false
        if (enteredApp != target || reopen) {
            if (enteringApp == target) return   // 等眼镜确认
            if (darkHome) {
                // 从关着屏的主界面打开页面：内容发完再开屏（和切换页面时的息屏一样，4 秒保险）
                darkHome = false
                blanked = true
                main.removeCallbacks(unblankSafety); main.postDelayed(unblankSafety, 4_000)
            }
            val current = enteredApp
            if (current != null) {
                // 眼镜不会从一个页面直接跳进另一个页面：要先退出当前页面，
                // 等眼镜回到主界面后再进入新页面。之前少了这一步，从看板切回阅读时眼镜没反应。
                // 退出后眼镜会先回到官方主界面，为了不让它闪一下，切换期间先把屏幕关掉，新内容发完再打开。
                log("切换页面：先息屏并退出当前页面")
                blankScreen()
                sendFrame(NimoFrameCodec.encodeFrame(
                    NimoProtocol.CMD_CONTROL_INSTRUCTION, NimoProtocol.CTRL_QUIT_APP, byteArrayOf(current.toByte())))
                enteredApp = null
                enteringApp = target
                main.postDelayed({
                    if (enteringApp == target) { enteringApp = null; enterApp(target); if (enteredApp == target) flush() }
                }, 500)
                return
            }
            enterApp(target)
            if (enteredApp != target) return
        }
        pending = null
        when (c) {
            is Content.Text -> sendTextContent(c)
            is Content.Image -> sendImageContent(c)
        }
        contentSerial++
        // 新内容已经发出，稍等眼镜画完再亮屏
        if (blanked) main.postDelayed({ unblankScreen() }, 350)
    }

    private var blanked = false
    private var needUnblankOnConnect = false

    /**
     * 下一份内容发之前把页面退出、重新打开一次（屏幕关着，看不到过程）。关屏久了眼镜可能已经悄悄收起了页面，
     * 我们以为页面还开着，往里发图就什么也不显示；重开一次和"回主界面后再叫回看板"走的是同一条可靠的路。
     */
    private var reopenNext = false
    fun reopenBeforeNextContent() { if (state == LinkState.READY && enteredApp != null) reopenNext = true }
    /** 下一份内容会先重开页面（重开时会暂时关屏、发完再开屏）。 */
    val reopenPending get() = reopenNext

    /** 已经发出去的内容份数，和"发送队列清空时已经发完的份数"：用来判断内容什么时候真正到了眼镜上。 */
    private var contentSerial = 0
    private var drainedSerial = 0
    private val shownWaiters = ArrayList<Pair<Int, () -> Unit>>()

    /**
     * 下一份内容全部发到眼镜、屏幕亮起之后回调一次。重新打开页面时要等页面确认、再传整张图，
     * 往往两三秒后才看得到，看板的翻屏、自动收起从这时才开始计时。
     */
    fun whenNextContentShown(cb: () -> Unit) { shownWaiters.add(contentSerial to cb) }
    private val unblankSafety = Runnable { unblankScreen() }

    /** 屏幕已经被我们关着：下一份内容发出去之后再开屏（不先露出上一次的画面）。 */
    fun unblankAfterNextContent() {
        if (state != LinkState.READY) return
        blanked = true
        main.removeCallbacks(unblankSafety)
        main.postDelayed(unblankSafety, 4_000)
    }

    /** 先暗着，等下一份内容发出去再亮（从主界面重新打开页面时，不先闪一下官方主界面）；页面还开着就不用。 */
    fun blankUntilShown() { if (state == LinkState.READY && enteredApp != imageApp) blankScreen() }

    private fun blankScreen() {
        blanked = true
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(1)))
        reportDisplayOff(true)
        // 保险：无论如何 4 秒后一定亮屏，避免卡在黑屏
        main.removeCallbacks(unblankSafety)
        main.postDelayed(unblankSafety, 4_000)
    }

    private fun unblankScreen() {
        if (!blanked) return
        blanked = false
        main.removeCallbacks(unblankSafety)
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DISPLAY_OFF, byteArrayOf(0)))
        reportDisplayOff(false)
    }

    private fun sendTextContent(c: Content.Text) {
        if (page == DisplayPage.PROMPTER) {
            NimoFrameCodec.updateContentFrames(
                NimoProtocol.APP_ID_PROMPTER, 0, NimoProtocol.PROMPTER_RES_TEXT,
                NimoProtocol.WIDGET_TEXT_PROMPTER, NimoFrameCodec.prompterTextContent(PROMPTER_TOP_PADDING + c.text)
            ).forEach { sendFrame(it) }
            c.status?.let { st ->
                NimoFrameCodec.updateContentFrames(
                    NimoProtocol.APP_ID_PROMPTER, 0, NimoProtocol.PROMPTER_RES_STATUS,
                    NimoProtocol.WIDGET_TEXT_PROMPTER, st.ifEmpty { " " }.toByteArray(Charsets.UTF_8)
                ).forEach { sendFrame(it) }
            }
        } else {
            NimoFrameCodec.updateContentFrames(
                NimoProtocol.APP_ID_ASR_NOTE, 0, 0, NimoProtocol.WIDGET_TEXT_NEW, c.text.toByteArray(Charsets.UTF_8)
            ).forEach { sendFrame(it) }
        }
    }

    /** 最近几张图从开始发送到全部写完用了多少毫秒（约等于眼镜每秒最多能刷新几张）。 */
    private val imageCosts = ArrayDeque<Long>()
    private var imageSendStart = 0L

    /** 发一张图平均要多少毫秒；还没发过几张时为 null。 */
    fun imageIntervalMs(): Long? = if (imageCosts.size < 2) null else imageCosts.sum() / imageCosts.size

    /** 发出去的画面张数和最近一次的时间（手机上显示，方便看眼镜有没有在更新）。 */
    var imagesSent = 0; private set
    var lastImageAt = 0L; private set

    private fun sendImageContent(c: Content.Image) {
        imageSendStart = System.currentTimeMillis()
        imagesSent++; lastImageAt = imageSendStart
        val frames = NimoFrameCodec.updateContentFrames(
            imageApp, 0, imageRes, NimoProtocol.WIDGET_PICTURE,
            NimoFrameCodec.imageContent(c.packed2bpp, c.width, c.height))
        frames.forEach { sendFrame(it) }
        val serial = ++imageSerial
        if (resendAfterEnter) {
            resendAfterEnter = false
            main.postDelayed({
                // 这期间没有发过新图、页面也还开着，才补发
                if (serial == imageSerial && enteredApp == imageApp && state == LinkState.READY && pending == null) frames.forEach { sendFrame(it) }
            }, 1200)
        }
    }

    /**
     * 打开眼镜上的某个页面，并等眼镜确认"已进入"之后再发内容。
     * 之前导航页是"发了就当成功"，刚打开的页面还没准备好就收到图片，图片会被丢掉，
     * 所以切到看板时要手动刷新一次才显示。
     */
    /** 实验：打开眼镜的语音笔记页。要先退出当前页面，所以稍等再进。 */
    fun openAsrPage() {
        if (state != LinkState.READY) return
        quitCurrent()
        main.postDelayed({ if (state == LinkState.READY) enterApp(NimoProtocol.APP_ID_ASR_NOTE) }, 500)
    }

    private fun enterApp(appId: Int) {
        enteringApp = appId
        val payload = if (appId == NimoProtocol.APP_ID_PROMPTER) {
            session++
            NimoFrameCodec.enterPrompterPayload(session, ByteArray(16).also { random.nextBytes(it) })
        } else byteArrayOf(appId.toByte(), NimoProtocol.APP_MODE_STANDALONE.toByte())
        log(when (appId) {
            NimoProtocol.APP_ID_PROMPTER -> "打开提词器页面"
            NimoProtocol.APP_ID_NAV -> "打开导航页"
            NimoProtocol.APP_ID_ASR_NOTE -> "打开语音笔记页"
            0x07 -> "打开 AI 对话页"
            else -> "打开页面 $appId"
        })
        sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_CONTROL_INSTRUCTION, NimoProtocol.CTRL_ENTER_APP, payload))
        enterTimeout?.let { main.removeCallbacks(it) }
        val t = Runnable {
            if (enteringApp == appId) {
                log("没收到页面打开的确认，直接发送内容")
                onAppEntered(appId)
            }
        }
        enterTimeout = t
        // 刚点亮主界面：眼镜忙着画主界面，头一秒左右发的"打开页面"常被丢掉（v5.11 只补发到 0.72 秒，抬头就叫不回看板了）。
        // 每隔 0.3 秒再发一次、一直发到 1.5 秒，每次紧跟着发一遍图；眼镜确认打开了就停
        val retry = android.os.SystemClock.uptimeMillis() - retryEnterAt < 2_000 && appId == imageApp
        if (retry) retryEnterAt = 0
        main.postDelayed(t, when {
            appId == NimoProtocol.APP_ID_PROMPTER -> 3_000L
            retry -> ENTER_RETRY_MS.last() + 300
            else -> NAV_ENTER_WAIT_MS
        })
        // 眼镜在这条路上一般不回"已打开"，也就不知道页面什么时候能收图：隔一会儿就发一次，
        // 哪次先被接住就先显示出来；到超时再正式发最后一次（顶替原来的"补发"）。
        // 一张图要一两百毫秒才传完，所以不能发得更密，否则堆在队列里反而更慢
        earlySent = false
        if (retry) {
            for (at in ENTER_RETRY_MS) main.postDelayed({
                if (enteringApp != appId || state != LinkState.READY) return@postDelayed
                sendFrame(NimoFrameCodec.encodeFrame(NimoProtocol.CMD_CONTROL_INSTRUCTION, NimoProtocol.CTRL_ENTER_APP, payload))
                main.postDelayed({
                    val c = pending as? Content.Image ?: return@postDelayed
                    if (enteringApp != appId || state != LinkState.READY) return@postDelayed
                    earlySent = true
                    NimoFrameCodec.updateContentFrames(imageApp, 0, imageRes, NimoProtocol.WIDGET_PICTURE,
                        NimoFrameCodec.imageContent(c.packed2bpp, c.width, c.height)).forEach { sendFrame(it) }
                }, 150)
            }, at)
        }
        if (appId == imageApp) for (at in EARLY_IMAGE_MS) main.postDelayed({
            val c = pending as? Content.Image ?: return@postDelayed
            if (enteringApp != appId || state != LinkState.READY) return@postDelayed
            earlySent = true
            NimoFrameCodec.updateContentFrames(imageApp, 0, imageRes, NimoProtocol.WIDGET_PICTURE,
                NimoFrameCodec.imageContent(c.packed2bpp, c.width, c.height)).forEach { sendFrame(it) }
            if (blanked) main.postDelayed({ unblankScreen() }, 350)
        }, at)
    }

    /** 下一次打开图片页面时多发几次"打开页面"（从刚点亮的官方主界面叫回看板时用）。 */
    fun retryNextEnter() { retryEnterAt = android.os.SystemClock.uptimeMillis() }
    private var retryEnterAt = 0L

    /** 这次打开页面已经抢发过图了（超时后那次正式发送就当作补发，不再额外补发）。 */
    private var earlySent = false

    private fun onAppEntered(appId: Int) {
        enterTimeout?.let { main.removeCallbacks(it) }
        enterTimeout = null
        enteringApp = null
        enteredApp = appId
        resendAfterEnter = !earlySent
        earlySent = false
        main.postDelayed({ flush() }, 150)
    }

    /** 刚打开页面后的第一张图要补发一次：实测眼镜刚进入页面时常常丢掉第一张（连上后、恢复显示后都只看到状态栏）。 */
    private var resendAfterEnter = false
    private var imageSerial = 0

    // ---------- 收包 ----------

    private fun handleRx(packet: ByteArray) {
        assembler.cleanup()
        for (frame in assembler.ingest(packet)) {
            val f = NimoFrameCodec.decode(frame) ?: continue
            val cmd = f.cmd ?: continue
            val key = f.key ?: continue
            val data = f.data ?: ByteArray(0)
            if (cmd == NimoProtocol.CMD_INSTRUCTION_REPORT) handleReport(key, data)
            else handleResponse(cmd, key, f.statusCode ?: 1, data)
        }
    }

    private fun handleReport(key: Int, data: ByteArray) {
        when (key) {
            NimoProtocol.REPORT_INPUT -> if (data.isNotEmpty()) handleInput(data[0].toInt() and 0xFF, data)
            NimoProtocol.REPORT_APP -> if (data.size >= 2) {
                val appId = data[0].toInt() and 0xFF
                val phase = data[1].toInt() and 0xFF
                if (phase == NimoProtocol.STATE_ENTER) {
                    if (enteringApp == appId) onAppEntered(appId)
                    else if (appId != enteredApp && enteringApp == null) {
                        if (enteredApp != null) log("眼镜上报：打开了页面 $appId，萤读的页面 $enteredApp 被顶替了")
                        enteredApp = null  // 被别的页面顶替了
                    }
                } else if (phase == NimoProtocol.STATE_EXIT && appId == enteredApp) {
                    log("眼镜上报：页面 $appId 已退出")
                    enteredApp = null
                }
            }
            NimoProtocol.REPORT_TWS -> if (data.isNotEmpty()) onTws((data[0].toInt() and 0xFF) >= 1)
            NimoProtocol.REPORT_DASH_WAKE -> onDashWake(data)
            !in listOf(NimoProtocol.REPORT_BUSINESS) -> log("眼镜上报 key=$key data=${data.joinToString(" ") { "%02x".format(it) }}")
            NimoProtocol.REPORT_BUSINESS -> if (data.isNotEmpty()) {
                val v = data.copyOfRange(1, data.size)
                when (data[0].toInt() and 0xFF) {
                    // 心跳里的 TWS 字节位置没核实过：只在握手时参考；连上以后只认眼镜专门发的 TWS 状态上报（key 3），免得误判断连、反复重开页面
                    NimoProtocol.BUSINESS_HEARTBEAT -> if (v.size >= 10 && state == LinkState.HANDSHAKING) onTws((v[8].toInt() and 0xFF) >= 1)
                    NimoProtocol.BUSINESS_BATTERY -> if (v.size >= 4) battery(v)
                    NimoProtocol.BUSINESS_AUTO_BRIGHTNESS -> if (v.isNotEmpty()) listener.onAutoBrightnessLevel((v[0].toInt() and 0xFF).coerceIn(0, NimoProtocol.MAX_BRIGHTNESS_LEVEL))
                }
            }
        }
    }

    private val inputNames = mapOf(
        0x01 to "抬头", 0x02 to "低头", 0x03 to "右单击", 0x04 to "右双击", 0x05 to "右长按",
        0x06 to "右按下", 0x07 to "右松开", 0x13 to "左单击", 0x14 to "左双击", 0x15 to "左长按",
        0x16 to "左按下", 0x17 to "左松开")

    /** 最近一次抬头/低头的时间（导航页里抬头低头时眼镜还会顺带发一条 cmd=7 key=9 状态 05，那不是退出）。 */
    private var lastHeadAt = 0L

    /** 最近一次收到"整理好的手势"（单击/双击/长按）的时间，用来判断要不要自己从按下/松开合成。 */
    private var lastGestureAt = 0L

    private fun handleInput(code: Int, raw: ByteArray) {
        log("镜腿/头部：${inputNames[code] ?: "未知"}（${raw.joinToString(" ") { "%02x".format(it) }}）")
        val input = when (code) {
            NimoProtocol.INPUT_CLICK_RIGHT -> GlassesInput.CLICK_RIGHT
            NimoProtocol.INPUT_CLICK_LEFT -> GlassesInput.CLICK_LEFT
            NimoProtocol.INPUT_DOUBLE_CLICK_RIGHT -> GlassesInput.DOUBLE_RIGHT
            NimoProtocol.INPUT_DOUBLE_CLICK_LEFT -> GlassesInput.DOUBLE_LEFT
            NimoProtocol.INPUT_LONG_PRESS_RIGHT, NimoProtocol.INPUT_LONG_PRESS_LEFT -> GlassesInput.LONG_PRESS
            NimoProtocol.INPUT_HEAD_UP -> GlassesInput.HEAD_UP
            NimoProtocol.INPUT_HEAD_DOWN -> GlassesInput.HEAD_DOWN
            NimoProtocol.INPUT_TOUCH_PRESS_RIGHT, NimoProtocol.INPUT_TOUCH_PRESS_LEFT -> { onTouchPress(code); return }
            NimoProtocol.INPUT_TOUCH_RELEASE_RIGHT, NimoProtocol.INPUT_TOUCH_RELEASE_LEFT -> { onTouchRelease(code); return }
            else -> return
        }
        if (input == GlassesInput.HEAD_UP || input == GlassesInput.HEAD_DOWN) lastHeadAt = android.os.SystemClock.uptimeMillis()
        else {
            lastGestureAt = System.currentTimeMillis()
            cancelSynth()
        }
        if (input == GlassesInput.LONG_PRESS && enteredApp == NimoProtocol.APP_ID_NAV) {
            // 导航页里长按右镜腿是眼镜自己的"退出"，不当作我们的操作
            onUserExited("长按镜腿")
            return
        }
        listener.onInput(input)
    }

    // ---- 在导航页里，眼镜可能只上报"按下/松开"而不上报单击，这里自己合成手势 ----
    private var pressAt = 0L
    private var pendingClickRight: Boolean? = null
    private val synthClick = Runnable {
        val right = pendingClickRight ?: return@Runnable
        pendingClickRight = null
        if (System.currentTimeMillis() - lastGestureAt < 600) return@Runnable  // 眼镜已经报过单击了，不重复
        log("（由按下/松开合成）${if (right) "右" else "左"}单击")
        listener.onInput(if (right) GlassesInput.CLICK_RIGHT else GlassesInput.CLICK_LEFT)
    }

    private fun cancelSynth() { main.removeCallbacks(synthClick); pendingClickRight = null }

    private fun onTouchPress(code: Int) { pressAt = System.currentTimeMillis() }

    private fun onTouchRelease(code: Int) {
        val right = code == NimoProtocol.INPUT_TOUCH_RELEASE_RIGHT
        val held = System.currentTimeMillis() - pressAt
        if (held > 700) {
            main.postDelayed({
                if (System.currentTimeMillis() - lastGestureAt > 600) {
                    log("（由按下/松开合成）长按")
                    listener.onInput(GlassesInput.LONG_PRESS)
                }
            }, 400)
            return
        }
        if (pendingClickRight == right) {
            // 第二次松开：双击
            cancelSynth()
            main.postDelayed({
                if (System.currentTimeMillis() - lastGestureAt > 600) {
                    log("（由按下/松开合成）${if (right) "右" else "左"}双击")
                    listener.onInput(if (right) GlassesInput.DOUBLE_RIGHT else GlassesInput.DOUBLE_LEFT)
                }
            }, 300)
            return
        }
        cancelSynth()
        pendingClickRight = right
        main.postDelayed(synthClick, 400)
    }

    /**
     * 官方主界面上抬头时（以及之后按刷新间隔定时），眼镜会发来 01 [起因] 03 01 04 00 [序号] 00 00 00；
     * 回一条确认：发给 app 0 / 资源 0xFF / 类型 3，内容 01 00 [序号] 00 00 00 01 04 [刷新间隔秒，2 字节] （实测这样眼镜才不重复上报）。
     */

    private fun onDashWake(data: ByteArray) {
        val hex = data.joinToString(" ") { "%02x".format(it) }
        val pull = NimoFrameCodec.parseDashPull(data)
        if (pull == null) { log("眼镜上报 key=6 data=$hex"); return }
        val content = NimoFrameCodec.dashPullState(NimoProtocol.DASH_STATE_ACCEPTED, pull.requestId, pull.left, pull.right, DASH_PULL_INTERVAL_SEC)
        NimoFrameCodec.updateContentFrames(NimoProtocol.APP_ID_HOME, 0, NimoProtocol.DASH_RES_STATE,
            NimoProtocol.DASH_RES_TYPE_STATE, content).forEach { sendFrame(it) }
        // 第 2 个字节是这次拉取的起因：00 = 抬头（主界面刚亮起），01 = 定时拉取（按回复里的刷新间隔，主界面亮着时会一直来）。
        // 只有抬头才叫回看板；定时拉取只回「收到」，也不记日志（不然看板收起后每隔一会儿又被叫起来）
        if (data[1].toInt() != 0) return
        log("眼镜主界面被点亮：抬头（$hex）")
        listener.onInput(GlassesInput.HOME_WAKE)
    }

    /** 设置原生看板右侧放哪张卡片（0 位置、1 待办、2 备忘录、3 新闻、4 股票），眼镜会记住。 */
    fun setDashRightLayout(layout: Int) {
        if (state != LinkState.READY) return
        sendAwaitingAck(NimoProtocol.CMD_SET_PARAMETER, NimoProtocol.SET_DASH_RIGHT_LAYOUT, byteArrayOf(layout.toByte())) { ok ->
            log(if (ok) "看板右侧卡片已设为 $layout" else "眼镜没有接受看板右侧卡片设置（$layout）")
        }
    }

    /** 眼镜自己退出了我们的页面（长按右镜腿）。 */
    private fun onUserExited(how: String) {
        log("眼镜退出了当前页面（$how）")
        enteredApp = null
        pending = null
        listener.onInput(GlassesInput.EXITED)
    }

    /**
     * 导航页发来 cmd=7 key=9 状态 05：长按右镜腿退出时会有，但实测抬头/低头时也会顺带发一条，
     * 那时页面其实还开着（v3.5 日志：当成退出后看板一直不自动收起）。
     * 所以前 1.5 秒、后 0.4 秒内有抬头低头的就不算退出。
     */
    private fun onNavAction05() {
        if (android.os.SystemClock.uptimeMillis() - lastHeadAt < 1_500) { log("眼镜随抬头/低头发来 05，页面还开着，不算退出"); return }
        main.postDelayed({
            if (enteredApp != NimoProtocol.APP_ID_NAV) return@postDelayed
            if (android.os.SystemClock.uptimeMillis() - lastHeadAt < 1_900) log("眼镜随抬头/低头发来 05，页面还开着，不算退出")
            else onUserExited("长按镜腿")
        }, 400)
    }

    private fun handleResponse(cmd: Int, key: Int, status: Int, data: ByteArray) {
        // cmd=7 key=9：眼镜把当前页面收到的镜腿操作转发给手机，数据就是操作代码
        // （实测：提词器里右单击 → 03；导航页长按右镜腿 → 05，此时眼镜已经退出页面）。
        if (cmd == NimoProtocol.CMD_CONTROL_INSTRUCTION && key == 9) {
            when (status) {
                NimoProtocol.INPUT_LONG_PRESS_RIGHT -> if (enteredApp == NimoProtocol.APP_ID_NAV) onNavAction05()
                else -> {}
            }
            return
        }
        if (status != 0) log("眼镜回复 cmd=$cmd key=$key status=$status data=${data.joinToString(" ") { "%02x".format(it) }}")
        pendingAcks.remove((cmd shl 8) or key)?.let {
            main.removeCallbacks(it.timeout)
            it.onResult(status == 0)
        }
        if (cmd != NimoProtocol.CMD_GET_PARAMETER || status != 0) return
        when (key) {
            NimoProtocol.GET_BATTERY -> if (data.size >= 4) battery(data)
            NimoProtocol.GET_TWS_STATUS -> if (data.isNotEmpty()) onTws((data[0].toInt() and 0xFF) >= 1)
            NimoProtocol.SET_HEADUP_DISPLAY, NimoProtocol.SET_DISPLAY_OFF, NimoProtocol.SET_AUTO_BRIGHTNESS ->
                if (data.isNotEmpty()) listener.onGlassesSetting(key, data[0].toInt() != 0)
            NimoProtocol.SET_BRIGHTNESS -> if (data.isNotEmpty()) {
                val lvl = (data[0].toInt() and 0xFF).coerceIn(0, NimoProtocol.MAX_BRIGHTNESS_LEVEL)
                log("眼镜亮度：$lvl 档"); listener.onBrightnessLevel(lvl)
            }
            NimoProtocol.GET_VERSION -> if (data.size >= 4) {
                val v = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) or
                    ((data[2].toInt() and 0xFF) shl 16) or ((data[3].toInt() and 0xFF) shl 24)
                listener.onFirmware("${(v ushr 28) and 0xF}.${(v ushr 21) and 0x7F}.${(v ushr 12) and 0x1FF}.${v and 0xFFF}")
            }
        }
    }

    private fun battery(d: ByteArray) {
        val level = minOf(d[0].toInt() and 0xFF, d[1].toInt() and 0xFF)
        listener.onBattery(level, d[2].toInt() == 1 || d[3].toInt() == 1)
    }

    // ---------- 写队列（Android 同一时刻只允许一个 GATT 操作） ----------

    private fun sendAwaitingAck(cmd: Int, key: Int, payload: ByteArray, onResult: (Boolean) -> Unit) {
        val ackKey = (cmd shl 8) or key
        pendingAcks.remove(ackKey)?.let { main.removeCallbacks(it.timeout); it.onResult(false) }
        val timeout = Runnable { pendingAcks.remove(ackKey)?.onResult?.invoke(false) }
        pendingAcks[ackKey] = PendingAck(onResult, timeout)
        main.postDelayed(timeout, ACK_TIMEOUT_MS)
        sendFrame(NimoFrameCodec.encodeFrame(cmd, key, payload, needsAck = true))
    }

    private fun sendFrame(frame: ByteArray) {
        if (tx == null) return
        writeQueue.addLast(frame)
        drainWrites()
    }

    @Suppress("DEPRECATION")
    private fun writeRaw(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = tx ?: return false
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // 超过 MTU 时自动走长写
        c.value = bytes
        return try { g.writeCharacteristic(c) } catch (e: Exception) { false }
    }

    private fun drainWrites() {
        if (writeInFlight) return
        while (gattOps.isNotEmpty() && gatt != null) {
            val op = gattOps.removeFirst()
            writeInFlight = true
            val started = try { op() } catch (e: Exception) { false }
            if (started) { armWatchdog(); return }
            writeInFlight = false
        }
        val bytes = writeQueue.removeFirstOrNull() ?: run {
            if (imageSendStart > 0) {
                imageCosts.addLast(System.currentTimeMillis() - imageSendStart)
                while (imageCosts.size > 6) imageCosts.removeFirst()
                imageSendStart = 0
            }
            if (drainedSerial != contentSerial) {
                drainedSerial = contentSerial
                val ready = shownWaiters.filter { it.first < contentSerial }
                if (ready.isNotEmpty()) {
                    shownWaiters.removeAll(ready)
                    // 亮屏指令排在内容后面 350 毫秒，眼镜画出来还要一点时间
                    main.postDelayed({ ready.forEach { it.second() } }, 450)
                }
            }
            if (flushWhenIdle) { flushWhenIdle = false; main.post { flush() } }
            return
        }
        if (gatt == null) { writeQueue.clear(); return }
        writeInFlight = true
        if (!writeRaw(bytes)) {
            writeInFlight = false
            writeQueue.addFirst(bytes)
            main.postDelayed({ drainWrites() }, 100)
            return
        }
        armWatchdog()
    }

    private fun armWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        val w = Runnable { if (writeInFlight) { writeInFlight = false; drainWrites() } }
        watchdog = w
        main.postDelayed(w, WRITE_WATCHDOG_MS)
    }

    // ---------- 麦克风 ----------

    /** 找麦克风的特征值，顺便把所有特征值记进日志。 */
    private fun findMic(g: BluetoothGatt) {
        val all = g.services.flatMap { it.characteristics }
        log("特征值：" + all.joinToString("；") { c ->
            val u = c.uuid.toString().let { if (it.endsWith("-0000-1000-8000-00805f9b34fb")) it.substring(4, 8) else it }
            "句柄 ${c.instanceId} $u" + (if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) " 通知" else "") +
                (if (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) " 写" else "")
        })
        mic = all.firstOrNull { it.instanceId == MIC_HANDLE }
        if (mic == null) log("没找到麦克风（句柄 $MIC_HANDLE）")
    }

    val micAvailable get() = mic != null
    var micOn = false; private set
    /** 麦克风的通知已经打开（每次连上只写一次描述符；间歇开麦时不用反复写）。 */
    private var micNotifyOn = false

    /** 开麦：打开通知，再写 52 01 00 00。 */
    @Suppress("DEPRECATION")
    fun micStart(): Boolean {
        val g = gatt ?: return false
        val c = mic ?: run { log("没找到眼镜麦克风"); return false }
        if (state != LinkState.READY) return false
        if (!micNotifyOn) {
            g.setCharacteristicNotification(c, true)
            micNotifyOn = true
            gattOps.addLast {
                val d = c.getDescriptor(NimoBLE.CCCD) ?: return@addLast false
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }
        gattOps.addLast { writeMic(g, c, NimoMic.START) }
        micOn = true
        log("眼镜麦克风：开")
        drainWrites()
        return true
    }

    fun micStop() {
        val g = gatt; val c = mic
        if (micOn && g != null && c != null) { gattOps.addLast { writeMic(g, c, NimoMic.STOP) }; drainWrites(); log("眼镜麦克风：关") }
        micOn = false
    }

    @Suppress("DEPRECATION")
    private fun writeMic(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean {
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = v
        return g.writeCharacteristic(c)
    }

    private var micNotes = 0
    private fun onMicData(v: ByteArray) {
        val frames = NimoMic.parse(v)
        if (frames.isEmpty()) {
            if (micNotes < 5) log("麦克风数据看不懂：${v.take(12).joinToString(" ") { "%02x".format(it) }}")
            micNotes++
            return
        }
        listener.onMicFrames(frames)
    }

    private fun resetSession() {
        // 如果断线时正处于切换页面的息屏中，重连后要先把屏幕打开
        if (blanked) { needUnblankOnConnect = true; blanked = false; main.removeCallbacks(unblankSafety) }
        if (darkHome) { needUnblankOnConnect = true; darkHome = false }
        twsConnected = false
        enteredApp = null
        enteringApp = null
        enterTimeout?.let { main.removeCallbacks(it) }
        enterTimeout = null
        tx = null
        rx = null
        assembler.reset()
        writeQueue.clear()
        gattOps.clear()
        mic = null
        micOn = false
        micNotifyOn = false
        shownWaiters.clear()
        writeInFlight = false
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        val acks = pendingAcks.values.toList()
        pendingAcks.clear()
        acks.forEach { main.removeCallbacks(it.timeout) }
    }

    private fun setState(s: LinkState) {
        if (state == s) return
        state = s
        listener.onLinkState(s)
    }

    private fun log(msg: String) = listener.onLog(msg)
}

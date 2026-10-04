// Nimo 眼镜蓝牙协议（帧编解码部分）
// 移植自 MentraOS（Apache License 2.0）的 Nimo 驱动，提交 2c91f97be（2026-07-17，动态画布之前的版本）。
// https://github.com/Mentra-Community/MentraOS
package io.github.yingdu

import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

object NimoBLE {
    val SERVICE_UUID: UUID = UUID.fromString("00007033-0000-1000-8000-00805F9B34FB")
    val CHAR_TX: UUID = UUID.fromString("00002021-0000-1000-8000-00805F9B34FB") // 手机 → 眼镜
    val CHAR_RX: UUID = UUID.fromString("00002022-0000-1000-8000-00805F9B34FB") // 眼镜 → 手机
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val NAME_PREFIX = "nimo"
    const val BLE_NAME_SUFFIX = "_ble" // iOS ANCS 旁路设备，不是数据通道
    const val CHUNK_SIZE = 501
    const val INTER_FRAME_DELAY_MS = 5L
}

object NimoProtocol {
    const val FRAME_MAGIC = 0xBF
    const val STATUS_ERR = 0x01
    const val STATUS_ACK = 0x02

    const val CMD_GET_PARAMETER = 0x02
    const val CMD_SET_PARAMETER = 0x03
    const val CMD_INSTRUCTION_REPORT = 0x06
    const val CMD_CONTROL_INSTRUCTION = 0x07
    const val CMD_CONTROL_NOTIFICATION = 0x09
    const val NOTIFICATION_SEND = 0x01

    const val GET_BATTERY = 0x06
    const val GET_VERSION = 0x0A
    const val GET_TWS_STATUS = 0x16

    const val SET_TIME = 0x01
    const val SET_BRIGHTNESS = 0x02
    const val SET_AUTO_BRIGHTNESS = 0x0E
    // 以下三项实机确认过（2026-09-27）：读取用 cmd=2，设置用 cmd=3，值 1 开 0 关
    const val SET_DISPLAY_OFF = 0x0F      // 息屏模式
    const val SET_HEADUP_DISPLAY = 0x0D   // 抬头显示：抬头亮屏、低头息屏，由眼镜自己判断
    // SET_AUTO_BRIGHTNESS = 0x0E 自动亮度（上面已定义）
    const val REPORT_DASH_WAKE = 0x06     // 抬头亮屏时眼镜发来的上报（要回一条确认）
    const val SET_PHONE_TYPE = 0x14

    const val CTRL_ENTER_APP = 0x01
    const val CTRL_QUIT_APP = 0x03
    const val CTRL_UPDATE_CONTENT = 0x04

    const val REPORT_INPUT = 0x01
    const val REPORT_APP = 0x02
    const val REPORT_TWS = 0x03
    const val REPORT_BUSINESS = 0x04

    const val BUSINESS_HEARTBEAT = 0x03
    const val BUSINESS_BATTERY = 0x05

    // v3.5 曾以为写反了，实测（用户描述 + v3.5 日志）01 确实是抬头、02 是低头
    const val INPUT_HEAD_UP = 0x01
    const val INPUT_HEAD_DOWN = 0x02
    const val INPUT_CLICK_RIGHT = 0x03
    const val INPUT_DOUBLE_CLICK_RIGHT = 0x04
    const val INPUT_LONG_PRESS_RIGHT = 0x05
    const val INPUT_TOUCH_PRESS_RIGHT = 0x06
    const val INPUT_TOUCH_RELEASE_RIGHT = 0x07
    const val INPUT_CLICK_LEFT = 0x13
    const val INPUT_DOUBLE_CLICK_LEFT = 0x14
    const val INPUT_LONG_PRESS_LEFT = 0x15
    const val INPUT_TOUCH_PRESS_LEFT = 0x16
    const val INPUT_TOUCH_RELEASE_LEFT = 0x17

    const val STATE_ENTER = 0x01
    const val STATE_EXIT = 0x03

    // 眼镜自带的"语音笔记"页面：旧固件上确认能直接显示推送文字，但显示区域较小
    const val APP_ID_ASR_NOTE = 0x04
    // 提词器页面：显示区域更大（实机确认，固件 V0.1.1.4）
    const val APP_ID_PROMPTER = 0x06
    const val PROMPTER_RES_TEXT = 0x00     // 正文
    const val PROMPTER_RES_STATUS = 0x01   // 顶部状态行
    const val WIDGET_TEXT_PROMPTER = 0x02
    const val APP_MODE_STANDALONE = 0x00
    const val WIDGET_TEXT_NEW = 0x00

    const val PHONE_TYPE_OTHER = 0x02

    // 导航页（appId 1）的大图区域：452×170，2bpp（每像素 4 级亮度）
    const val APP_ID_NAV = 0x01
    const val NAV_RES_LARGE_MAP = 0x05
    const val NAV_LARGE_MAP_WIDTH = 452
    const val NAV_LARGE_MAP_HEIGHT = 170
    const val WIDGET_PICTURE = 0x80
    const val COM_IMAGE_HEADER = 0x16
    const val FORMAT_2BPP = 0x02
    const val COMPRESSION_NONE = 0x00
    const val COMPRESSION_ZLIB = 0x07
    const val MAX_BRIGHTNESS_LEVEL = 16

    // 眼镜主界面的原生看板：左边天气/运动，右边一张卡片（位置 / 待办 / 备忘录 / 新闻 / 股票）
    const val SET_DASH_RIGHT_LAYOUT = 0x0C  // 右侧放哪张卡片
    const val SET_DASH_LEFT_LAYOUT = 0x18
    const val DASH_RIGHT_LOCATION = 0       // 位置卡片：显示手机发来的一张图
    const val DASH_RIGHT_STOCK = 4
    const val DASH_LEFT_WEATHER = 1
    const val APP_ID_HOME = 0x00
    const val DASH_RES_STATE = 0xFF         // 回应眼镜拉取的状态帧
    const val DASH_RES_TYPE_STATE = 0x03
    const val DASH_STATE_ACCEPTED = 0
    const val DASH_MAP_WIDTH = 304          // 位置卡片的图片大小
    const val DASH_MAP_HEIGHT = 180
}

/** CRC-16/CCITT-FALSE：init 0xFFFF，poly 0x1021，不反转，无末尾异或。 */
fun nimoCrc16(data: ByteArray): Int {
    var crc = 0xFFFF
    for (byte in data) {
        crc = crc xor ((byte.toInt() and 0xFF) shl 8)
        for (i in 0 until 8) {
            crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
        }
    }
    return crc
}

/**
 * 帧格式（小端）：
 * 8 字节传输头：magic, status, len(2), crc16(2), index(2)
 * 请求的应用头：cmd, key, len(2)；回复和上报多一个状态字节：cmd, key, len(2), status, data
 */
object NimoFrameCodec {

    fun transportHeader(payload: ByteArray, index: Int = 0, needsAck: Boolean = true): ByteArray {
        val h = ByteArray(8)
        h[0] = NimoProtocol.FRAME_MAGIC.toByte()
        h[1] = (if (needsAck) NimoProtocol.STATUS_ACK else 0).toByte()
        h[2] = (payload.size and 0xFF).toByte()
        h[3] = ((payload.size shr 8) and 0xFF).toByte()
        val crc = nimoCrc16(payload)
        h[4] = (crc and 0xFF).toByte()
        h[5] = ((crc shr 8) and 0xFF).toByte()
        h[6] = (index and 0xFF).toByte()
        h[7] = ((index shr 8) and 0xFF).toByte()
        return h
    }

    fun applicationHeader(cmd: Int, key: Int, payloadSize: Int): ByteArray = byteArrayOf(
        cmd.toByte(), key.toByte(),
        (payloadSize and 0xFF).toByte(), ((payloadSize shr 8) and 0xFF).toByte()
    )

    fun encodeFrame(
        cmd: Int, key: Int, payload: ByteArray = ByteArray(0),
        index: Int = 0, needsAck: Boolean = true
    ): ByteArray {
        val tp = applicationHeader(cmd, key, payload.size) + payload
        return transportHeader(tp, index, needsAck) + tp
    }

    /** 内容更新按 501 字节切片；分片序号规则：最后一片 = 0，其余 = i+1；只有一片时 = 0。 */
    fun updateContentFrames(
        appId: Int, layoutId: Int, resId: Int, resType: Int,
        content: ByteArray, chunkSize: Int = NimoBLE.CHUNK_SIZE
    ): List<ByteArray> {
        val full = applicationHeader(
            NimoProtocol.CMD_CONTROL_INSTRUCTION, NimoProtocol.CTRL_UPDATE_CONTENT, 4 + content.size
        ) + byteArrayOf(appId.toByte(), layoutId.toByte(), resId.toByte(), resType.toByte()) + content
        val count = (full.size + chunkSize - 1) / chunkSize
        val frames = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val chunk = full.copyOfRange(i * chunkSize, minOf((i + 1) * chunkSize, full.size))
            val index = if (i == count - 1) 0 else i + 1
            frames.add(transportHeader(chunk, index) + chunk)
        }
        return frames
    }

    /**
     * 进入提词器页面，发送的是：
     * appId, mode, 00 00 00 01 05 02 00, 会话序号(u32，每次进入递增), 16 字节会话标识。
     * 16 字节标识没能对上文本的 MD5/SHA，看起来是随机值，这里用随机数。
     */
    fun enterPrompterPayload(session: Int, token: ByteArray): ByteArray {
        require(token.size == 16)
        return byteArrayOf(
            NimoProtocol.APP_ID_PROMPTER.toByte(), NimoProtocol.APP_MODE_STANDALONE.toByte(),
            0, 0, 0, 1, 5, 2, 0,
            (session and 0xFF).toByte(), ((session shr 8) and 0xFF).toByte(),
            ((session shr 16) and 0xFF).toByte(), ((session shr 24) and 0xFF).toByte()
        ) + token
    }

    /**
     * 提词器正文内容：13 字节头 + UTF-8 文本 + 结尾 0。
     * 头部：u32 0, u16 文本字节数, u32 滚动相关参数(静态页填 0), u16 同上, u8 0。
     */
    fun prompterTextContent(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        val header = byteArrayOf(
            0, 0, 0, 0,
            (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(),
            0, 0, 0, 0,
            0, 0,
            0
        )
        return header + bytes + byteArrayOf(0)
    }

    /**
     * 眼镜在主界面亮起时发来的看板拉取请求（cmd=6 key=6）：
     * 版本(1), 标志, 要什么(位 1 = 要内容), 左侧卡片, 右侧卡片, 标志, 请求号(u32)。
     * （实测：左边天气、右边股票时是 01 00 03 01 04 …）
     */
    data class DashPull(val scope: Int, val left: Int, val right: Int, val requestId: Long) {
        val wantsContent get() = scope and 0x02 != 0
    }

    fun parseDashPull(d: ByteArray): DashPull? {
        if (d.size < 7 || d[0].toInt() != 1) return null
        var id = 0L
        for (k in 0 until minOf(4, d.size - 6)) id = id or ((d[6 + k].toLong() and 0xFF) shl (8 * k))
        return DashPull(d[2].toInt() and 0xFF, d[3].toInt() and 0xFF, d[4].toInt() and 0xFF, id)
    }

    /** 回应拉取的状态帧内容：版本 1, 状态, 请求号(u32), 左侧卡片, 右侧卡片, 多少秒后再来拉(u16)。 */
    fun dashPullState(state: Int, requestId: Long, left: Int, right: Int, intervalSec: Int): ByteArray {
        val b = ByteArray(10)
        b[0] = 1; b[1] = state.toByte()
        for (k in 0 until 4) b[2 + k] = ((requestId shr (8 * k)) and 0xFF).toByte()
        b[6] = left.toByte(); b[7] = right.toByte()
        b[8] = (intervalSec and 0xFF).toByte(); b[9] = ((intervalSec shr 8) and 0xFF).toByte()
        return b
    }

    /** 亮度 0..255 → 2bpp（4 像素一字节，高位在前）。 */
    fun pack2bpp(levels: ByteArray): ByteArray {
        val out = ByteArray((levels.size + 3) shr 2)
        for (i in out.indices) {
            var packed = 0
            for (j in 0 until 4) {
                val idx = i * 4 + j
                val v = if (idx < levels.size) {
                    val px = levels[idx].toInt() and 0xFF
                    when { px < 0x40 -> 0; px < 0x80 -> 1; px < 0xC0 -> 2; else -> 3 }
                } else 0
                packed = packed or (v shl (6 - 2 * j))
            }
            out[i] = packed.toByte()
        }
        return out
    }

    /**
     * 图片内容 = 15 字节头 + 像素数据（zlib 压缩更小时用压缩）。
     * 头部：0x16, 宽(u16), 高(u16), 格式, 压缩方式, 原始字节数(u32), 压缩后字节数(u32，未压缩为 0)。
     */
    fun imageContent(packed: ByteArray, width: Int, height: Int): ByteArray {
        val d = java.util.zip.Deflater()
        d.setInput(packed); d.finish()
        val bos = ByteArrayOutputStream(packed.size)
        val buf = ByteArray(4096)
        while (!d.finished()) bos.write(buf, 0, d.deflate(buf))
        d.end()
        val z = bos.toByteArray()
        val useZ = z.size < packed.size
        val payload = if (useZ) z else packed
        val h = ByteArray(15)
        h[0] = NimoProtocol.COM_IMAGE_HEADER.toByte()
        h[1] = (width and 0xFF).toByte(); h[2] = ((width shr 8) and 0xFF).toByte()
        h[3] = (height and 0xFF).toByte(); h[4] = ((height shr 8) and 0xFF).toByte()
        h[5] = NimoProtocol.FORMAT_2BPP.toByte()
        h[6] = (if (useZ) NimoProtocol.COMPRESSION_ZLIB else NimoProtocol.COMPRESSION_NONE).toByte()
        val orig = packed.size
        for (k in 0 until 4) h[7 + k] = ((orig shr (8 * k)) and 0xFF).toByte()
        val cs = if (useZ) z.size else 0
        for (k in 0 until 4) h[11 + k] = ((cs shr (8 * k)) and 0xFF).toByte()
        return h + payload
    }

    /** 9 字节时间：[year(2)][月][日][时][分][秒][星期 周日=0][时区 以 15 分钟为单位]。 */
    fun encodeDeviceTime(timeMillis: Long = System.currentTimeMillis()): ByteArray {
        val cal = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val b = ByteArray(9)
        val year = cal.get(Calendar.YEAR)
        b[0] = (year and 0xFF).toByte()
        b[1] = ((year shr 8) and 0xFF).toByte()
        b[2] = (cal.get(Calendar.MONTH) + 1).toByte()
        b[3] = cal.get(Calendar.DAY_OF_MONTH).toByte()
        b[4] = cal.get(Calendar.HOUR_OF_DAY).toByte()
        b[5] = cal.get(Calendar.MINUTE).toByte()
        b[6] = cal.get(Calendar.SECOND).toByte()
        b[7] = (cal.get(Calendar.DAY_OF_WEEK) - 1).toByte()
        val zone = (TimeZone.getDefault().getOffset(timeMillis) / 60_000 / 15.0).toInt().coerceIn(-48, 48)
        b[8] = (zone and 0xFF).toByte()
        return b
    }

    data class DecodedFrame(
        val transportStatus: Int, val index: Int,
        val cmd: Int?, val key: Int?, val statusCode: Int?, val data: ByteArray?
    )

    /** 解码一个完整帧；传输错误、CRC 不对或被截断时返回 null。 */
    fun decode(frame: ByteArray): DecodedFrame? {
        if (frame.size < 8) return null
        if ((frame[0].toInt() and 0xFF) != NimoProtocol.FRAME_MAGIC) return null
        val status = frame[1].toInt() and 0xFF
        val len = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        val crc = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
        val index = (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8)
        if (frame.size < 8 + len) return null
        if (status and NimoProtocol.STATUS_ERR != 0) return null
        val payload = frame.copyOfRange(8, 8 + len)
        if (nimoCrc16(payload) != crc) return null
        if (payload.size < 5) return DecodedFrame(status, index, null, null, null, null)
        return DecodedFrame(
            status, index,
            payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF, payload[4].toInt() and 0xFF,
            if (payload.size > 5) payload.copyOfRange(5, payload.size) else ByteArray(0)
        )
    }
}

/** 多包回复重组：按 (cmd,key) 分组，index 1 为首片，0 为末片。 */
class NimoReceiveAssembler {
    private class Pending {
        var firstAppPayload: ByteArray? = null
        val dataByIndex = mutableMapOf<Int, ByteArray>()
        val startTime = System.currentTimeMillis()
    }

    private val pending = mutableMapOf<Int, Pending>()

    fun ingest(packet: ByteArray): List<ByteArray> {
        if (packet.size < 8) return emptyList()
        val len = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        val index = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
        if (packet.size < 8 + len) return emptyList()
        val app = packet.copyOfRange(8, 8 + len)
        if (app.size < 2) return if (index == 0) listOf(packet) else emptyList()

        val group = ((app[0].toInt() and 0xFF) shl 8) or (app[1].toInt() and 0xFF)
        if (index == 0) {
            val p = pending.remove(group) ?: return listOf(packet)
            p.dataByIndex[Int.MAX_VALUE] = dataSection(app)
            return listOf(reframe(p))
        }
        if (index == 1 && pending[group]?.firstAppPayload != null) pending.remove(group)
        val p = pending.getOrPut(group) { Pending() }
        if (index == 1) p.firstAppPayload = app
        p.dataByIndex[index] = dataSection(app)
        return emptyList()
    }

    fun cleanup(timeoutMs: Long = 10_000) {
        val now = System.currentTimeMillis()
        pending.entries.removeAll { now - it.value.startTime >= timeoutMs }
    }

    fun reset() = pending.clear()

    private fun dataSection(app: ByteArray) =
        if (app.size <= 5) ByteArray(0) else app.copyOfRange(5, app.size)

    private fun reframe(p: Pending): ByteArray {
        val merged = ByteArrayOutputStream()
        for (k in p.dataByIndex.keys.sorted()) merged.write(p.dataByIndex[k]!!)
        val data = merged.toByteArray()
        val first = p.firstAppPayload
        val app = ByteArray(5 + data.size)
        app[0] = if (first != null && first.isNotEmpty()) first[0] else 0
        app[1] = if (first != null && first.size >= 2) first[1] else 0
        app[2] = (data.size and 0xFF).toByte()
        app[3] = ((data.size shr 8) and 0xFF).toByte()
        app[4] = if (first != null && first.size >= 5) first[4] else 0
        data.copyInto(app, 5)
        return NimoFrameCodec.transportHeader(app, 0) + app
    }
}

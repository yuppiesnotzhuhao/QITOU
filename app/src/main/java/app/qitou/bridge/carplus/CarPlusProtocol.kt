// SPDX-License-Identifier: AGPL-3.0-or-later
// CarLife HU-side wire protocol adapted from CarPToCarL (MIT, Copyright (c) 2026 lizhihua).
package app.qitou.bridge.carplus

import java.io.ByteArrayOutputStream

/**
 * Minimal CarLife head-unit protocol used by the local phone CarConnect bridge.
 *
 * Car+ and Honor CarConnect expose the standard CarLife sender on loopback while
 * their CarLife wireless-connection flow is active. QITOU connects as the head
 * unit, negotiates the legacy plaintext 3.0 profile, and receives the same H.264
 * Annex-B stream that the phone-side app would send to a real CarLife dashboard.
 */
internal object CarPlusProtocol {
    const val PORT_CMD = 7240
    const val PORT_VIDEO = 8240
    const val PORT_MEDIA = 9240
    const val PORT_TTS = 9241
    const val PORT_VR = 9242
    const val PORT_TOUCH = 9340
    const val PORT_DATA = 9440

    val PORTS = intArrayOf(
        PORT_CMD,
        PORT_VIDEO,
        PORT_MEDIA,
        PORT_TTS,
        PORT_VR,
        PORT_TOUCH,
        PORT_DATA,
    )

    const val HU_PROTOCOL_VERSION = 98305
    const val MD_VERSION_MATCH = 65538
    const val HU_STATISTICS = 98343
    const val MD_INFO = 65540
    const val HU_INFO = 98307
    const val HU_VIDEO_ENC_INIT = 98311
    const val VIDEO_ENC_START = 98313
    const val MD_VIDEO_ENC_INIT = 65544
    const val VIDEO_ENC_DONE = 65551
    const val HU_FEATURE_CONFIG = 98386
    const val MD_FEATURE_CONFIG_DONE = 65570
    const val MD_MODULE_STATUS = 65574
    const val HU_MODULE_CONTROL = 98344
    const val HEARTBEAT = 131074

    const val TOUCH_ACTION = 425985
    const val TOUCH_DOWN = 425986
    const val TOUCH_UP = 425987
    const val TOUCH_MOVE = 425988

    val MD_IGNORED_IDS = intArrayOf(65560, 65563)

    fun command(serviceType: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = (payload.size ushr 8).toByte()
        out[1] = payload.size.toByte()
        out[4] = (serviceType ushr 24).toByte()
        out[5] = (serviceType ushr 16).toByte()
        out[6] = (serviceType ushr 8).toByte()
        out[7] = serviceType.toByte()
        payload.copyInto(out, 8)
        return out
    }

    /**
     * Protocol 3.0 is intentional. Newer CarLife builds interpret 4.0 as the
     * encrypted vehicle-certificate profile, which never reaches plaintext video.
     */
    fun version(): ByteArray = varint(1, 3) + varint(2, 0)

    fun statistics(): ByteArray =
        text(1, "12345678") +
            text(2, "4.0.0") +
            varint(3, 16) +
            text(4, "20029999")

    /** The first eight CarlifeDeviceInfo fields are sufficient for the legacy HU path. */
    fun headUnitInfo(display: String): ByteArray =
        text(1, "Android") +
            text(2, "sm8750") +
            text(3, "unknown") +
            text(4, "OPPO") +
            text(5, "arm64-v8a") +
            text(6, "arm64-v8a") +
            text(7, "QITOU") +
            text(8, display)

    fun featureConfig(): ByteArray {
        val items = listOf(
            text(1, "I_FRAME_INTERVAL") + varint(2, 20),
            text(1, "CONNECT_TYPE") + varint(2, 5),
            text(1, "DISABLE_RESOLUTION_OPTION") + varint(2, 1),
            text(1, "CONTENT_ENCRYPTION") + varint(2, 0),
            text(1, "MEDIA_LYRIC_SUPPORT") + varint(2, 1),
        )
        var out = varint(1, items.size)
        for (item in items) out += bytes(2, item)
        out += varint(3, 0)
        out += text(4, "QITOU")
        out += text(5, "02:00:00:00:00:00")
        return out
    }

    fun videoEncoderInfo(width: Int, height: Int, fps: Int): ByteArray =
        varint(1, width) +
            varint(2, height) +
            varint(3, fps) +
            varint(8, 0) +
            varint(9, 0)

    fun moduleControl(ids: List<Int>): ByteArray {
        var out = varint(1, ids.size)
        for (id in ids) out += bytes(2, varint(1, id) + varint(2, 1))
        return out
    }

    fun touch(action: Int, x: Int, y: Int): ByteArray =
        varint(1, action) + varint(2, x.coerceAtLeast(0)) + varint(3, y.coerceAtLeast(0))

    fun touchPoint(x: Int, y: Int): ByteArray =
        varint(1, x.coerceAtLeast(0)) + varint(2, y.coerceAtLeast(0))

    fun readVarints(payload: ByteArray, maxFields: Int = 16): Map<Int, Long> {
        val result = LinkedHashMap<Int, Long>()
        var index = 0
        while (index < payload.size && result.size < maxFields) {
            val tag = readVarint(payload, index) ?: break
            index = tag.second
            val field = (tag.first ushr 3).toInt()
            val wire = (tag.first and 7L).toInt()
            when (wire) {
                0 -> {
                    val value = readVarint(payload, index) ?: break
                    result[field] = value.first
                    index = value.second
                }
                2 -> {
                    val length = readVarint(payload, index) ?: break
                    index = length.second
                    val end = (index + length.first).coerceAtMost(payload.size.toLong()).toInt()
                    index = end
                }
                1 -> index += 8
                5 -> index += 4
                else -> break
            }
        }
        return result
    }

    fun parseModuleStatus(payload: ByteArray): List<Pair<Int, Int>> {
        val values = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index < payload.size) {
            val tag = readVarint(payload, index) ?: break
            index = tag.second
            if ((tag.first ushr 3).toInt() != 2 || (tag.first and 7L).toInt() != 2) continue
            val length = readVarint(payload, index) ?: break
            index = length.second
            val end = (index + length.first).coerceAtMost(payload.size.toLong()).toInt()
            val fields = readVarints(payload.copyOfRange(index, end), 4)
            val id = fields[1]?.toInt()
            val status = fields[2]?.toInt()
            if (id != null && status != null) values += id to status
            index = end
        }
        return values
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var index = start
        while (index < data.size && shift <= 63) {
            val next = data[index++].toInt() and 0xff
            value = value or ((next and 0x7f).toLong() shl shift)
            if (next and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    private fun varint(field: Int, value: Int): ByteArray {
        var number = value.toLong()
        val out = ByteArrayOutputStream()
        writeVarint(out, field.toLong() shl 3)
        while (true) {
            val low = (number and 0x7f).toInt()
            number = number ushr 7
            if (number == 0L) {
                out.write(low)
                return out.toByteArray()
            }
            out.write(low or 0x80)
        }
    }

    private fun bytes(field: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, ((field.toLong() shl 3) or 2L))
        writeVarint(out, value.size.toLong())
        out.write(value)
        return out.toByteArray()
    }

    private fun text(field: Int, value: String): ByteArray =
        bytes(field, value.toByteArray(Charsets.UTF_8))

    private fun writeVarint(out: ByteArrayOutputStream, input: Long) {
        var value = input
        while ((value and 0x7f.inv().toLong()) != 0L) {
            out.write(((value and 0x7f) or 0x80).toInt())
            value = value ushr 7
        }
        out.write(value.toInt() and 0x7f)
    }
}

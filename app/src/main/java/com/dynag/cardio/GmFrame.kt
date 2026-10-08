package com.dynag.cardio

/**
 * Encoder for the MemoMind GM (Glass Messaging) packet framing, plus the
 * Web Bridge application-message envelope used to draw text on the glasses HUD.
 *
 * Reference: GlassSDK/docs/PROTOCOL.md (GM packet framing, Plugin application
 * service 0x0F) and GlassSDK/docs/HUD_PROTOCOL.md (application-message envelope).
 *
 * A logical GM packet:
 *   byte 0        frame head: 0xFA for the first frame, 0x01.. for continuations
 *   bytes 1..3    total logical packet length, unsigned big-endian (24-bit)
 *   byte 4        event ID
 *   byte 5        service ID
 *   byte 6        command ID
 *   bytes 7..n    TLVs / continuation payload
 *   last 2 bytes  additive checksum of all preceding frame bytes, mod 65536 (BE)
 *
 * TLV layout: one type byte, three-byte big-endian value length, then the value.
 *
 * The Web Bridge downlink for the glasses is:
 *   service 0x0F, command 0x28
 *   TLV1 INT16 (0x07) = application channel
 *   TLV2 BYTES (0x01) = application payload
 *
 * Example wire vectors are validated against GlassSDK/docs/WIRE_EXAMPLES.md.
 */
object GmFrame {

    const val FRAME_HEAD_FIRST: Int = 0xFA
    const val SERVICE_PLUGIN: Int = 0x0F
    const val COMMAND_DELIVER: Int = 0x28

    // TLV types
    const val TLV_BYTES: Int = 0x01
    const val TLV_STRING: Int = 0x02
    const val TLV_INT32: Int = 0x03
    const val TLV_JSON: Int = 0x05
    const val TLV_STATUS: Int = 0x06
    const val TLV_INT16: Int = 0x07
    const val TLV_INT8: Int = 0x08

    // Web Bridge application channels
    const val CH_CLEAR: Int = 1
    const val CH_TEXT: Int = 2
    const val CH_RECT: Int = 3
    const val CH_DELETE: Int = 4
    const val CH_LINE: Int = 5
    const val CH_BITMAP: Int = 6

    /**
     * Encode a single TLV (type + 24-bit big-endian length + value).
     */
    fun tlv(type: Int, value: ByteArray): ByteArray {
        val out = ByteArray(4 + value.size)
        out[0] = (type and 0xFF).toByte()
        out[1] = ((value.size ushr 16) and 0xFF).toByte()
        out[2] = ((value.size ushr 8) and 0xFF).toByte()
        out[3] = (value.size and 0xFF).toByte()
        System.arraycopy(value, 0, out, 4, value.size)
        return out
    }

    fun tlvInt16(type: Int, value: Int): ByteArray {
        return tlv(type, byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte()))
    }

    fun tlvInt8(type: Int, value: Int): ByteArray {
        return tlv(type, byteArrayOf((value and 0xFF).toByte()))
    }

    fun tlvInt32(type: Int, value: Long): ByteArray {
        return tlv(type, byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        ))
    }

    /**
     * Build a complete, single physical GM frame from an event/service/command
     * and a body (already-encoded TLV stream).
     *
     * The returned frame includes the 0xFA head and the 2-byte additive checksum.
     * Callers must keep each physical frame within the negotiated BLE write limit.
     * This helper does not fragment; use [fragment] when the logical packet is
     * larger than the write limit.
     */
    fun singleFrame(eventId: Int, service: Int, command: Int, body: ByteArray): ByteArray {
        val logicalLen = 9 + body.size
        val frame = ByteArray(logicalLen)
        frame[0] = FRAME_HEAD_FIRST.toByte()
        frame[1] = ((logicalLen ushr 16) and 0xFF).toByte()
        frame[2] = ((logicalLen ushr 8) and 0xFF).toByte()
        frame[3] = (logicalLen and 0xFF).toByte()
        frame[4] = (eventId and 0xFF).toByte()
        frame[5] = (service and 0xFF).toByte()
        frame[6] = (command and 0xFF).toByte()
        System.arraycopy(body, 0, frame, 7, body.size)
        putChecksum(frame)
        return frame
    }

    /**
     * Fragment a logical body into one or more physical GM frames so that each
     * physical frame stays within [maxFrameBytes] (default 512, the SDK ceiling).
     * Each physical frame repeats the logical length, event, service and command
     * and carries its own checksum, per the SDK framing rules.
     *
     * For BLE, keep one complete physical frame per characteristic write and
     * callers should pace continuation frames in order (0x01, 0x02, ...).
     */
    fun fragment(
        eventId: Int,
        service: Int,
        command: Int,
        body: ByteArray,
        maxFrameBytes: Int = 512
    ): List<ByteArray> {
        require(maxFrameBytes > 9) { "maxFrameBytes must leave room for header+checksum" }
        val logicalLen = 9 + body.size
        val bodyPerFrame = maxFrameBytes - 9
        val frames = ArrayList<ByteArray>()
        var offset = 0
        var head = FRAME_HEAD_FIRST
        var first = true
        while (first || offset < body.size) {
            val remaining = body.size - offset
            val take = if (remaining > bodyPerFrame) bodyPerFrame else remaining
            val frame = ByteArray(7 + take + 2)
            frame[0] = (head and 0xFF).toByte()
            frame[1] = ((logicalLen ushr 16) and 0xFF).toByte()
            frame[2] = ((logicalLen ushr 8) and 0xFF).toByte()
            frame[3] = (logicalLen and 0xFF).toByte()
            frame[4] = (eventId and 0xFF).toByte()
            frame[5] = (service and 0xFF).toByte()
            frame[6] = (command and 0xFF).toByte()
            System.arraycopy(body, offset, frame, 7, take)
            putChecksum(frame)
            frames.add(frame)
            offset += take
            first = false
            head = if (head == FRAME_HEAD_FIRST) 0x01 else (head + 1)
        }
        return frames
    }

    private fun putChecksum(frame: ByteArray) {
        var sum = 0
        for (i in 0 until frame.size - 2) {
            sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFFFF
        }
        frame[frame.size - 2] = ((sum ushr 8) and 0xFF).toByte()
        frame[frame.size - 1] = (sum and 0xFF).toByte()
    }

    // ---------------------------------------------------------------------
    // Web Bridge application payloads (channel bytes)
    // ---------------------------------------------------------------------

    /** Channel 1 clear: one compatibility byte. */
    fun clearPayload(): ByteArray = byteArrayOf(0x00)

    /**
     * Channel 2 text.
     * Layout: id:u8 x:u16 y:u16 width:u16 height:u16 border:u8 radius:u8 utf8...
     * All multi-byte integers big-endian.
     */
    fun textPayload(
        id: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        border: Int,
        radius: Int,
        text: String
    ): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(11 + utf8.size)
        out[0] = (id and 0xFF).toByte()
        putU16(out, 1, x)
        putU16(out, 3, y)
        putU16(out, 5, width)
        putU16(out, 7, height)
        out[9] = (border and 0xFF).toByte()
        out[10] = (radius and 0xFF).toByte()
        System.arraycopy(utf8, 0, out, 11, utf8.size)
        return out
    }

    /** Channel 4 delete: object id. */
    fun deletePayload(id: Int): ByteArray = byteArrayOf((id and 0xFF).toByte())

    private fun putU16(dst: ByteArray, at: Int, v: Int) {
        dst[at] = ((v ushr 8) and 0xFF).toByte()
        dst[at + 1] = (v and 0xFF).toByte()
    }

    // ---------------------------------------------------------------------
    // High-level helpers for the Web Bridge text channel
    // ---------------------------------------------------------------------

    /**
     * Build a complete GM frame that draws [text] as object [id] on the glasses,
     * via the Web Bridge text channel (service 0x0F, command 0x28, channel 2).
     */
    fun webBridgeText(
        eventId: Int,
        id: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        border: Int,
        radius: Int,
        text: String,
        maxFrameBytes: Int = 512
    ): List<ByteArray> {
        val payload = textPayload(id, x, y, width, height, border, radius, text)
        val body = tlvInt16(TLV_INT16, CH_TEXT) + tlv(TLV_BYTES, payload)
        return fragment(eventId, SERVICE_PLUGIN, COMMAND_DELIVER, body, maxFrameBytes)
    }

    /** Build a complete GM frame that clears the Web Bridge scene (channel 1). */
    fun webBridgeClear(eventId: Int): ByteArray {
        val body = tlvInt16(TLV_INT16, CH_CLEAR) + tlv(TLV_BYTES, clearPayload())
        return singleFrame(eventId, SERVICE_PLUGIN, COMMAND_DELIVER, body)
    }
}

package expo.modules.glasssdk.util

/**
 * Re-containerizes the glasses' recorded audio into a standard Ogg/Opus file.
 *
 * The glasses store audio as a raw Opus bitstream — back-to-back fixed-size (40-byte) CBR Opus
 * packets with NO container (the repeating 0x4B 0x41 is just the constant Opus TOC/config byte of the
 * fixed-bitrate packets). No player can open that. This wraps the packets into a proper Ogg/Opus
 * stream (OpusHead + OpusTags + Ogg audio pages with CRC32). Pure byte work — no decoding — so it
 * matches the iOS/JS implementations exactly. Validated against a known-good device recording
 * (452×40-byte packets -> 48 kHz mono).
 */
object OpusOggWrapper {
    private const val OPUS_RATE = 48000
    private const val PACKET_DURATION_MS = 20 // device uses SILK-WB 20 ms frames

    /**
     * Wrap a raw Opus-packet stream into Ogg/Opus. Returns the input unchanged if it is already Ogg
     * (starts with "OggS") or the packet layout can't be detected.
     */
    fun wrapIfNeeded(raw: ByteArray): ByteArray {
        if (isOgg(raw)) return raw
        val packets = detectPackets(raw)
        if (packets.isNullOrEmpty()) return raw
        return buildOggOpus(packets)
    }

    // ---- Packet detection ----

    private fun isOgg(r: ByteArray): Boolean =
        r.size >= 4 && r[0] == 0x4F.toByte() && r[1] == 0x67.toByte() &&
            r[2] == 0x67.toByte() && r[3] == 0x53.toByte()

    private fun detectPackets(raw: ByteArray): List<ByteArray>? =
        parseLengthPrefixed(raw, 2, true)
            ?: parseLengthPrefixed(raw, 2, false)
            ?: parseLengthPrefixed(raw, 1, false)
            ?: guessFixedSize(raw)

    private fun parseLengthPrefixed(raw: ByteArray, bytes: Int, littleEndian: Boolean): List<ByteArray>? {
        val packets = ArrayList<ByteArray>()
        var i = 0
        while (i + bytes <= raw.size) {
            val len = if (bytes == 1) {
                raw[i].toInt() and 0xFF
            } else {
                val b0 = raw[i].toInt() and 0xFF
                val b1 = raw[i + 1].toInt() and 0xFF
                if (littleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
            }
            i += bytes
            if (len <= 0 || len > 2000) return null
            if (i + len > raw.size) return null
            packets.add(raw.copyOfRange(i, i + len))
            i += len
        }
        if (i != raw.size) return null
        return if (packets.size >= 3) packets else null
    }

    private fun guessFixedSize(raw: ByteArray): List<ByteArray>? {
        // Device records back-to-back fixed-size Opus packets; 40 bytes is what these glasses use.
        for (size in intArrayOf(40, 60, 80, 100, 120, 160, 200, 240, 320)) {
            if (raw.size % size != 0) continue
            val count = raw.size / size
            if (count < 5) continue
            val packets = ArrayList<ByteArray>(count)
            var i = 0
            while (i < raw.size) {
                packets.add(raw.copyOfRange(i, i + size))
                i += size
            }
            return packets
        }
        return null
    }

    // ---- Ogg building ----

    private fun buildOggOpus(packets: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        val serial = 0x1a2b3c4d
        var seq = 0
        var granule = 0L

        writeOggPage(out, serial, seq++, 0L, 0x02, listOf(buildOpusHead(1, 0)))
        writeOggPage(out, serial, seq++, 0L, 0x00, listOf(buildOpusTags("glass")))

        val samplesPerPacket = (PACKET_DURATION_MS.toLong() * OPUS_RATE) / 1000L // 960
        var idx = 0
        while (idx < packets.size) {
            val pagePackets = ArrayList<ByteArray>()
            var segCount = 0
            while (idx < packets.size) {
                val p = packets[idx]
                var needed = (p.size + 254) / 255
                if (p.size % 255 == 0) needed += 1
                if (segCount + needed > 255) break
                pagePackets.add(p)
                segCount += needed
                granule += samplesPerPacket
                idx++
            }
            val isLast = idx >= packets.size
            writeOggPage(out, serial, seq++, granule, if (isLast) 0x04 else 0x00, pagePackets)
        }

        return out.toByteArray()
    }

    private fun buildOpusHead(channels: Int, preSkip: Int): ByteArray {
        val b = ArrayList<Byte>()
        b.addAscii("OpusHead")
        b.add(1)              // version
        b.add((channels and 0xFF).toByte())
        b.le16(preSkip)
        b.le32(OPUS_RATE)     // input sample rate (informational)
        b.le16(0)             // output gain
        b.add(0)              // channel mapping family
        return b.toByteArray()
    }

    private fun buildOpusTags(vendor: String): ByteArray {
        val b = ArrayList<Byte>()
        b.addAscii("OpusTags")
        val vb = vendor.toByteArray(Charsets.UTF_8)
        b.le32(vb.size)
        for (x in vb) b.add(x)
        b.le32(0)             // user comment count
        return b.toByteArray()
    }

    private fun writeOggPage(
        out: ArrayList<Byte>,
        serial: Int,
        seq: Int,
        granule: Long,
        headerType: Int,
        packets: List<ByteArray>,
    ) {
        val seg = ArrayList<Byte>()
        val payload = ArrayList<Byte>()
        for (p in packets) {
            var remaining = p.size
            var offset = 0
            while (remaining > 0) {
                val s = minOf(255, remaining)
                seg.add(s.toByte())
                for (i in 0 until s) payload.add(p[offset + i])
                offset += s
                remaining -= s
            }
            if (p.size % 255 == 0) seg.add(0) // lacing terminator
        }

        val page = ArrayList<Byte>()
        page.addAscii("OggS")          // 0..3
        page.add(0)                    // 4: version
        page.add((headerType and 0xFF).toByte()) // 5
        page.le64(granule)             // 6..13
        page.le32(serial)              // 14..17
        page.le32(seq)                 // 18..21
        page.le32(0)                   // 22..25: CRC placeholder
        page.add(seg.size.toByte())    // 26: segment count
        page.addAll(seg)
        page.addAll(payload)

        val crc = oggCrc(page)
        page[22] = (crc and 0xFF).toByte()
        page[23] = ((crc ushr 8) and 0xFF).toByte()
        page[24] = ((crc ushr 16) and 0xFF).toByte()
        page[25] = ((crc ushr 24) and 0xFF).toByte()

        out.addAll(page)
    }

    // ---- LE writers + Ogg CRC32 ----

    private fun ArrayList<Byte>.add(v: Int) = add(v.toByte())
    private fun ArrayList<Byte>.addAscii(s: String) { for (c in s) add(c.code.toByte()) }
    private fun ArrayList<Byte>.le16(v: Int) { add(v and 0xFF); add((v shr 8) and 0xFF) }
    private fun ArrayList<Byte>.le32(v: Int) {
        add(v and 0xFF); add((v shr 8) and 0xFF); add((v shr 16) and 0xFF); add((v ushr 24) and 0xFF)
    }
    private fun ArrayList<Byte>.le64(v: Long) {
        var x = v
        for (i in 0 until 8) { add((x and 0xFF).toInt()); x = x ushr 8 }
    }

    // Ogg CRC32: poly 0x04C11DB7, no reflection, init 0.
    private val crcTable: IntArray = run {
        val t = IntArray(256)
        for (i in 0 until 256) {
            var r = i shl 24
            for (j in 0 until 8) {
                r = if ((r and 0x80000000.toInt()) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1
            }
            t[i] = r
        }
        t
    }

    private fun oggCrc(data: List<Byte>): Int {
        var crc = 0
        for (b in data) {
            val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor crcTable[idx]
        }
        return crc
    }
}

import Foundation

/// Re-containerizes the glasses' recorded audio into a standard Ogg/Opus file.
///
/// The glasses store audio as a raw Opus bitstream — back-to-back fixed-size (40-byte) CBR Opus
/// packets with NO container (the repeating `0x4B 0x41` is just the constant Opus TOC/config byte of
/// the fixed-bitrate packets). No player can open that. This wraps the packets into a proper
/// Ogg/Opus stream (OpusHead + OpusTags + Ogg audio pages with CRC32). Pure byte work — no decoding —
/// so it matches the JS/Android implementations exactly. Validated against a known-good device
/// recording (452×40-byte packets → 48 kHz mono).
struct OpusOggWrapper {
    private static let opusRate = 48000
    private static let packetDurationMs = 20 // device uses SILK-WB 20 ms frames

    /// Wrap a raw Opus-packet stream into Ogg/Opus. Returns the input unchanged if it is already Ogg
    /// (starts with "OggS") or the packet layout can't be detected.
    static func wrapIfNeeded(_ data: Data) -> Data {
        let raw = [UInt8](data)
        if isOgg(raw) { return data }
        guard let packets = detectPackets(raw), !packets.isEmpty else { return data }
        return Data(buildOggOpus(packets))
    }

    // MARK: - Packet detection

    private static func isOgg(_ r: [UInt8]) -> Bool {
        r.count >= 4 && r[0] == 0x4F && r[1] == 0x67 && r[2] == 0x67 && r[3] == 0x53
    }

    private static func detectPackets(_ raw: [UInt8]) -> [[UInt8]]? {
        parseLengthPrefixed(raw, bytes: 2, littleEndian: true)
            ?? parseLengthPrefixed(raw, bytes: 2, littleEndian: false)
            ?? parseLengthPrefixed(raw, bytes: 1, littleEndian: false)
            ?? guessFixedSize(raw)
    }

    private static func parseLengthPrefixed(_ raw: [UInt8], bytes: Int, littleEndian: Bool) -> [[UInt8]]? {
        var packets: [[UInt8]] = []
        var i = 0
        while i + bytes <= raw.count {
            let len: Int
            if bytes == 1 {
                len = Int(raw[i])
            } else {
                len = littleEndian ? Int(raw[i]) | (Int(raw[i + 1]) << 8)
                                   : (Int(raw[i]) << 8) | Int(raw[i + 1])
            }
            i += bytes
            if len <= 0 || len > 2000 { return nil }
            if i + len > raw.count { return nil }
            packets.append(Array(raw[i..<i + len]))
            i += len
        }
        if i != raw.count { return nil }
        return packets.count >= 3 ? packets : nil
    }

    private static func guessFixedSize(_ raw: [UInt8]) -> [[UInt8]]? {
        // Device records back-to-back fixed-size Opus packets; 40 bytes is what these glasses use.
        for size in [40, 60, 80, 100, 120, 160, 200, 240, 320] {
            if raw.count % size != 0 { continue }
            let count = raw.count / size
            if count < 5 { continue }
            var packets: [[UInt8]] = []
            var i = 0
            while i < raw.count {
                packets.append(Array(raw[i..<i + size]))
                i += size
            }
            return packets
        }
        return nil
    }

    // MARK: - Ogg building

    private static func buildOggOpus(_ packets: [[UInt8]]) -> [UInt8] {
        var out: [UInt8] = []
        let serial: UInt32 = 0x1a2b_3c4d
        var seq: UInt32 = 0
        var granule: UInt64 = 0

        writeOggPage(&out, serial, seq, 0, 0x02, [buildOpusHead(channels: 1, preSkip: 0)]); seq += 1
        writeOggPage(&out, serial, seq, 0, 0x00, [buildOpusTags(vendor: "glass")]); seq += 1

        let samplesPerPacket = UInt64(packetDurationMs * opusRate / 1000) // 960
        var idx = 0
        while idx < packets.count {
            var pagePackets: [[UInt8]] = []
            var segCount = 0
            while idx < packets.count {
                let p = packets[idx]
                var needed = (p.count + 254) / 255
                if p.count % 255 == 0 { needed += 1 }
                if segCount + needed > 255 { break }
                pagePackets.append(p)
                segCount += needed
                granule += samplesPerPacket
                idx += 1
            }
            let isLast = idx >= packets.count
            writeOggPage(&out, serial, seq, granule, isLast ? 0x04 : 0x00, pagePackets); seq += 1
        }
        return out
    }

    private static func buildOpusHead(channels: Int, preSkip: Int) -> [UInt8] {
        var b = Array("OpusHead".utf8)
        b.append(1) // version
        b.append(UInt8(channels & 0xFF))
        appendLE16(&b, preSkip)
        appendLE32(&b, opusRate) // input sample rate (informational)
        appendLE16(&b, 0)        // output gain
        b.append(0)              // channel mapping family
        return b
    }

    private static func buildOpusTags(vendor: String) -> [UInt8] {
        var b = Array("OpusTags".utf8)
        let vb = Array(vendor.utf8)
        appendLE32(&b, vb.count)
        b.append(contentsOf: vb)
        appendLE32(&b, 0) // user comment count
        return b
    }

    private static func writeOggPage(
        _ out: inout [UInt8],
        _ serial: UInt32,
        _ seq: UInt32,
        _ granule: UInt64,
        _ headerType: UInt8,
        _ packets: [[UInt8]]
    ) {
        var seg: [UInt8] = []
        var payload: [UInt8] = []
        for p in packets {
            var remaining = p.count
            var offset = 0
            while remaining > 0 {
                let s = min(255, remaining)
                seg.append(UInt8(s))
                payload.append(contentsOf: p[offset..<offset + s])
                offset += s
                remaining -= s
            }
            if p.count % 255 == 0 { seg.append(0) } // lacing terminator
        }

        var page = Array("OggS".utf8)        // 0..3
        page.append(0)                        // 4: version
        page.append(headerType)               // 5
        appendLE64(&page, granule)            // 6..13
        appendLE32u(&page, serial)            // 14..17
        appendLE32u(&page, seq)               // 18..21
        appendLE32u(&page, 0)                 // 22..25: CRC placeholder
        page.append(UInt8(seg.count))         // 26: segment count
        page.append(contentsOf: seg)
        page.append(contentsOf: payload)

        let crc = oggCrc(page)
        page[22] = UInt8(crc & 0xFF)
        page[23] = UInt8((crc >> 8) & 0xFF)
        page[24] = UInt8((crc >> 16) & 0xFF)
        page[25] = UInt8((crc >> 24) & 0xFF)

        out.append(contentsOf: page)
    }

    // MARK: - LE writers + Ogg CRC32

    private static func appendLE16(_ b: inout [UInt8], _ v: Int) {
        b.append(UInt8(v & 0xFF))
        b.append(UInt8((v >> 8) & 0xFF))
    }

    private static func appendLE32(_ b: inout [UInt8], _ v: Int) {
        b.append(UInt8(v & 0xFF))
        b.append(UInt8((v >> 8) & 0xFF))
        b.append(UInt8((v >> 16) & 0xFF))
        b.append(UInt8((v >> 24) & 0xFF))
    }

    private static func appendLE32u(_ b: inout [UInt8], _ v: UInt32) {
        b.append(UInt8(v & 0xFF))
        b.append(UInt8((v >> 8) & 0xFF))
        b.append(UInt8((v >> 16) & 0xFF))
        b.append(UInt8((v >> 24) & 0xFF))
    }

    private static func appendLE64(_ b: inout [UInt8], _ v: UInt64) {
        var x = v
        for _ in 0..<8 {
            b.append(UInt8(x & 0xFF))
            x >>= 8
        }
    }

    // Ogg CRC32: poly 0x04C11DB7, no reflection, init 0.
    private static let crcTable: [UInt32] = {
        var t = [UInt32](repeating: 0, count: 256)
        for i in 0..<256 {
            var r = UInt32(i) << 24
            for _ in 0..<8 {
                r = (r & 0x8000_0000) != 0 ? (r << 1) ^ 0x04C1_1DB7 : r << 1
            }
            t[i] = r
        }
        return t
    }()

    private static func oggCrc(_ data: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0
        for b in data {
            let idx = Int(((crc >> 24) ^ UInt32(b)) & 0xFF)
            crc = (crc << 8) ^ crcTable[idx]
        }
        return crc
    }
}

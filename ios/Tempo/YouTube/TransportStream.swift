import Foundation

/// Just enough MPEG transport stream parsing to pull out the AAC (ADTS) audio.
enum TransportStream {
    static func extractAAC(from data: Data) -> Data {
        let bytes = [UInt8](data)
        var output = Data()
        var pmtPID: Int?
        var audioPID: Int?
        var offset = 0
        // Skip anything before the first sync byte.
        while offset < bytes.count, bytes[offset] != 0x47 { offset += 1 }

        while offset + 188 <= bytes.count {
            defer { offset += 188 }
            guard bytes[offset] == 0x47 else {
                // Lost sync: find the next packet start.
                var next = offset + 1
                while next < bytes.count, bytes[next] != 0x47 { next += 1 }
                offset = next - 188
                continue
            }
            let pid = (Int(bytes[offset + 1] & 0x1F) << 8) | Int(bytes[offset + 2])
            let unitStart = bytes[offset + 1] & 0x40 != 0
            let adaptation = (bytes[offset + 3] >> 4) & 0x3
            var start = offset + 4
            if adaptation == 2 { continue }
            if adaptation == 3 { start += 1 + Int(bytes[offset + 4]) }
            let end = offset + 188
            guard start < end else { continue }

            if pid == 0 {
                if let found = programMapPID(bytes, start, end, unitStart) { pmtPID = found }
            } else if pid == pmtPID {
                if let found = aacPID(bytes, start, end, unitStart) { audioPID = found }
            } else if pid == audioPID {
                var payload = start
                if unitStart {
                    // PES header: 00 00 01, stream id, length (2), flags (2), header length, header.
                    guard payload + 9 <= end, bytes[payload] == 0, bytes[payload + 1] == 0, bytes[payload + 2] == 1 else { continue }
                    payload += 9 + Int(bytes[payload + 8])
                }
                if payload < end { output.append(contentsOf: bytes[payload..<end]) }
            }
        }
        return output
    }

    private static func section(_ bytes: [UInt8], _ start: Int, _ end: Int, _ unitStart: Bool) -> Int? {
        guard unitStart else { return nil }
        let sectionStart = start + 1 + Int(bytes[start]) // pointer field
        return sectionStart < end ? sectionStart : nil
    }

    private static func programMapPID(_ bytes: [UInt8], _ start: Int, _ end: Int, _ unitStart: Bool) -> Int? {
        guard let s = section(bytes, start, end, unitStart), s + 12 <= end else { return nil }
        let length = (Int(bytes[s + 1] & 0x0F) << 8) | Int(bytes[s + 2])
        var i = s + 8
        while i + 4 <= min(end, s + 3 + length - 4) {
            let program = (Int(bytes[i]) << 8) | Int(bytes[i + 1])
            if program != 0 { return (Int(bytes[i + 2] & 0x1F) << 8) | Int(bytes[i + 3]) }
            i += 4
        }
        return nil
    }

    private static func aacPID(_ bytes: [UInt8], _ start: Int, _ end: Int, _ unitStart: Bool) -> Int? {
        guard let s = section(bytes, start, end, unitStart), s + 12 <= end else { return nil }
        let length = (Int(bytes[s + 1] & 0x0F) << 8) | Int(bytes[s + 2])
        let infoLength = (Int(bytes[s + 10] & 0x0F) << 8) | Int(bytes[s + 11])
        var i = s + 12 + infoLength
        while i + 5 <= min(end, s + 3 + length - 4) {
            let type = bytes[i]
            let pid = (Int(bytes[i + 1] & 0x1F) << 8) | Int(bytes[i + 2])
            let esInfo = (Int(bytes[i + 3] & 0x0F) << 8) | Int(bytes[i + 4])
            if type == 0x0F { return pid } // AAC in ADTS
            i += 5 + esInfo
        }
        return nil
    }
}

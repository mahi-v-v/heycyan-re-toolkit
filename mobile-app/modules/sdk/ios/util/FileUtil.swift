import Foundation

struct FileUtil {
    static func detectContentType(path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg": return "image/jpeg"
        case "png":          return "image/png"
        case "gif":          return "image/gif"
        case "webp":         return "image/webp"
        case "bmp":          return "image/bmp"
        case "svg":          return "image/svg+xml"
        case "mp4":          return "video/mp4"
        case "mov":          return "video/quicktime"
        case "avi":          return "video/x-msvideo"
        case "mkv":          return "video/x-matroska"
        case "webm":         return "video/webm"
        case "mp3":          return "audio/mpeg"
        case "wav":          return "audio/wav"
        case "ogg":          return "audio/ogg"
        case "opus":         return "audio/opus"
        case "aac":          return "audio/aac"
        case "m4a":          return "audio/mp4"
        case "pdf":          return "application/pdf"
        case "txt":          return "text/plain"
        case "json":         return "application/json"
        case "xml":          return "application/xml"
        case "csv":          return "text/csv"
        default:             return "application/octet-stream"
        }
    }
}

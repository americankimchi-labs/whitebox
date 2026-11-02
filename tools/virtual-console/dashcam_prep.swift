// Prepares a downloaded source clip for the dashcam stand-in: keeps <seconds> from <start>, crops <top> and <bottom>
// pixels (a source dashcam's own date or ID strip, which would contradict the demo's clock), and writes H.264 video
// without sound (dashcam_mux.swift adds the dashcam's sound later).
//
//     swift tools/virtual-console/dashcam_prep.swift <source> <out.mp4> <start> <seconds> <top> <bottom>
//
// macOS only (AVFoundation); dashcam.py uses ffmpeg elsewhere.
import AVFoundation

let args = CommandLine.arguments
guard args.count == 7, let start = Double(args[3]), let seconds = Double(args[4]),
      let top = Double(args[5]), let bottom = Double(args[6]) else {
    FileHandle.standardError.write("usage: dashcam_prep.swift <source> <out.mp4> <start> <seconds> <top> <bottom>\n".data(using: .utf8)!)
    exit(2)
}
let source = AVURLAsset(url: URL(fileURLWithPath: args[1]))
let out = URL(fileURLWithPath: args[2])

let done = DispatchSemaphore(value: 0)
var failed = false
Task {
    do {
        guard let track = try await source.loadTracks(withMediaType: .video).first else {
            throw NSError(domain: "dashcam", code: 1, userInfo: [NSLocalizedDescriptionKey: "no video track"])
        }
        let size = try await track.load(.naturalSize)
        let range = CMTimeRange(start: CMTime(seconds: start, preferredTimescale: 600),
                                duration: CMTime(seconds: seconds, preferredTimescale: 600))
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw NSError(domain: "dashcam", code: 2, userInfo: [NSLocalizedDescriptionKey: "cannot compose"])
        }
        try videoTrack.insertTimeRange(range, of: track, at: .zero)

        // Even dimensions for the encoder.
        let height = (size.height - top - bottom).rounded(.down)
        let video = AVMutableVideoComposition()
        video.renderSize = CGSize(width: size.width, height: height - height.truncatingRemainder(dividingBy: 2))
        video.frameDuration = CMTime(value: 1, timescale: 30)
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: range.duration)
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
        layer.setTransform(CGAffineTransform(translationX: 0, y: -top), at: .zero)
        instruction.layerInstructions = [layer]
        video.instructions = [instruction]

        guard let export = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
            throw NSError(domain: "dashcam", code: 3, userInfo: [NSLocalizedDescriptionKey: "cannot export"])
        }
        export.videoComposition = video
        try? FileManager.default.removeItem(at: out)
        if #available(macOS 15, *) {
            try await export.export(to: out, as: .mp4)
        } else {
            export.outputURL = out
            export.outputFileType = .mp4
            await export.export()
            if let error = export.error { throw error }
        }
    } catch {
        FileHandle.standardError.write("dashcam_prep: \(error.localizedDescription)\n".data(using: .utf8)!)
        failed = true
    }
    done.signal()
}
done.wait()
exit(failed ? 1 : 0)

// Puts a dashcam's sound into a video clip, as a dashcam that heard the car's sync chirp would have recorded it.
//
//     swift tools/virtual-console/dashcam_mux.swift <video> <audio.wav> <out.mov> <seconds>
//
// The first <seconds> of the video are kept unchanged (passthrough) and the WAV becomes its audio track, also
// unchanged: uncompressed PCM, as many dashcams record it, so the 21–22 kHz chirp survives (an AAC encoder would
// cut that band). macOS only (AVFoundation); dashcam.py falls back to ffmpeg elsewhere.
import AVFoundation

let args = CommandLine.arguments
guard args.count == 5, let seconds = Double(args[4]) else {
    FileHandle.standardError.write("usage: dashcam_mux.swift <video> <audio.wav> <out.mov> <seconds>\n".data(using: .utf8)!)
    exit(2)
}
let video = AVURLAsset(url: URL(fileURLWithPath: args[1]))
let audio = AVURLAsset(url: URL(fileURLWithPath: args[2]))
let out = URL(fileURLWithPath: args[3])

let done = DispatchSemaphore(value: 0)
var failed = false
Task {
    do {
        let range = CMTimeRange(start: .zero, duration: CMTime(seconds: seconds, preferredTimescale: 600))
        let composition = AVMutableComposition()
        guard let sourceVideo = try await video.loadTracks(withMediaType: .video).first,
              let sourceAudio = try await audio.loadTracks(withMediaType: .audio).first,
              let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        else { throw NSError(domain: "dashcam", code: 1, userInfo: [NSLocalizedDescriptionKey: "missing video or audio track"]) }
        try videoTrack.insertTimeRange(range, of: sourceVideo, at: .zero)
        videoTrack.preferredTransform = try await sourceVideo.load(.preferredTransform)
        try audioTrack.insertTimeRange(range, of: sourceAudio, at: .zero)
        guard let export = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else {
            throw NSError(domain: "dashcam", code: 2, userInfo: [NSLocalizedDescriptionKey: "cannot export"])
        }
        try? FileManager.default.removeItem(at: out)
        // The movie header first, as a dashcam writes it: the phone reads the start time from the file's beginning.
        export.shouldOptimizeForNetworkUse = true
        if #available(macOS 15, *) {
            try await export.export(to: out, as: .mov)
        } else {
            export.outputURL = out
            export.outputFileType = .mov
            await export.export()
            if let error = export.error { throw error }
        }
    } catch {
        FileHandle.standardError.write("dashcam_mux: \(error.localizedDescription)\n".data(using: .utf8)!)
        failed = true
    }
    done.signal()
}
done.wait()
exit(failed ? 1 : 0)

package com.nathanhanapps.wayd.ai

/** IMAGE also carries video, sampled into frames before it reaches an engine; no engine here takes raw video. */
enum class MediaKind { IMAGE, AUDIO }

/**
 * One user-attached item, already reduced to what a model accepts: a JPEG of bounded size, or 16 kHz mono 16-bit WAV.
 * [label] is shown to the user and the model (for example "video frame 2 at 3.0 s"); [id] is citeable as `u:N`.
 * Bytes live in memory for one conversation only and are never stored.
 */
class AiMedia(val id: String, val kind: MediaKind, val mime: String, val bytes: ByteArray, val label: String) {
    init {
        require(id.matches(Regex("u:[0-9]{1,3}")))
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        require(mime == "image/jpeg" || mime == "audio/wav")
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        /** Gemma's audio encoder works on clips of about half a minute; longer clips are cut by the capture helpers. */
        const val MAX_AUDIO_SECONDS = 30
    }
}

class AiMediaUnsupported(val kind: MediaKind) : Exception("media_unsupported_$kind")

/** Attachment limits per context size; images cost the most prompt tokens on small models. */
data class MediaLimits(val images: Int, val audioClips: Int) {
    companion object {
        fun of(size: ContextSize): MediaLimits = when (size) {
            ContextSize.SMALL -> MediaLimits(images = 2, audioClips = 1)
            ContextSize.MEDIUM -> MediaLimits(images = 4, audioClips = 1)
            ContextSize.LARGE, ContextSize.AUTO -> MediaLimits(images = 6, audioClips = 1)
        }
    }
}

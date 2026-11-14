package com.nathanhanapps.wayd.feature.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import com.nathanhanapps.wayd.ai.AiMedia
import com.nathanhanapps.wayd.ai.MediaKind
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reduces user-picked media to what the models accept, in memory only: JPEG ≤ 768 px, video as a few evenly spaced frames, audio as
 * 16 kHz mono 16-bit WAV of at most 30 s. Nothing is written to disk or the database. Read errors return null rather than a partial item.
 */
object ChatMedia {
    private const val MAX_EDGE = 768
    const val RATE = 16_000
    private val MAX_SAMPLES = RATE * AiMedia.MAX_AUDIO_SECONDS

    fun image(context: Context, uri: Uri, id: String, label: String): AiMedia? = runCatching {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = max(info.size.width, info.size.height).toFloat() / MAX_EDGE
            if (scale > 1f) decoder.setTargetSize((info.size.width / scale).roundToInt(), (info.size.height / scale).roundToInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        AiMedia(id, MediaKind.IMAGE, "image/jpeg", jpeg(bitmap), label)
    }.getOrNull()

    /** Video becomes [count] frames; its sound track is not sent (say so in the UI). */
    fun videoFrames(context: Context, uri: Uri, firstId: Int, count: Int, name: String): List<AiMedia> = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (1..count).mapNotNull { k ->
                val atMs = if (durationMs > 0) durationMs * k / (count + 1) else 0L
                val frame = r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_EDGE, MAX_EDGE) ?: return@mapNotNull null
                AiMedia("u:${firstId + k - 1}", MediaKind.IMAGE, "image/jpeg", jpeg(frame), "$name · frame $k at %.1f s".format(atMs / 1000.0))
            }
        } finally {
            r.release()
        }
    }.getOrDefault(emptyList())

    fun audio(context: Context, uri: Uri, id: String, label: String): AiMedia? = runCatching {
        decode(context, uri)?.takeIf { it.isNotEmpty() }?.let { AiMedia(id, MediaKind.AUDIO, "audio/wav", wav(it), label) }
    }.getOrNull()

    fun voice(samples: ShortArray, id: String, label: String): AiMedia? =
        samples.takeIf { it.size >= RATE / 4 }?.let { AiMedia(id, MediaKind.AUDIO, "audio/wav", wav(it), label) }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }

    /** Platform decoder → mono → linear resample to 16 kHz, stopping once 30 s are collected. */
    private fun decode(context: Context, uri: Uri): ShortArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mono = ArrayList<Short>(MAX_SAMPLES)
            val limit = { (MAX_SAMPLES.toLong() * rate / RATE).toInt() }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && mono.size < limit()) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                            rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            val pcm = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = pcm.remaining() / channels.coerceAtLeast(1)
                            for (f in 0 until frames) {
                                var sum = 0
                                for (c in 0 until channels) sum += pcm.get(f * channels + c)
                                mono += (sum / channels).toShort()
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return resample(mono, rate)
        } finally {
            extractor.release()
        }
    }

    private fun resample(input: List<Short>, rate: Int): ShortArray {
        if (rate == RATE) return input.take(MAX_SAMPLES).toShortArray()
        val n = minOf((input.size.toLong() * RATE / rate).toInt(), MAX_SAMPLES)
        return ShortArray(n) { i ->
            val pos = i.toDouble() * rate / RATE
            val a = pos.toInt().coerceAtMost(input.lastIndex)
            val b = (a + 1).coerceAtMost(input.lastIndex)
            val t = pos - a
            (input[a] * (1 - t) + input[b] * t).roundToInt().toShort()
        }
    }

    fun wav(samples: ShortArray, rate: Int = RATE): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }
}

/**
 * Push-to-talk capture for one chat question: 16 kHz mono PCM in memory, stopped by the user or after 30 s. Only runs while the
 * chat screen holds it and the user granted RECORD_AUDIO; nothing is recorded in the background or saved.
 */
class VoiceRecorder {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val buffer = ArrayList<Short>()

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO right before start()
    fun start(onLimit: () -> Unit): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(ChatMedia.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val record = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, ChatMedia.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2) }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }
        synchronized(buffer) { buffer.clear() }
        running = true
        thread = Thread {
            val chunk = ShortArray(min)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) synchronized(buffer) { for (i in 0 until n) buffer += chunk[i] }
                    if (synchronized(buffer) { buffer.size } >= ChatMedia.RATE * AiMedia.MAX_AUDIO_SECONDS) { running = false; onLimit() }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }.apply { name = "wayd-voice"; start() }
        return true
    }

    fun stop(): ShortArray {
        running = false
        thread?.join(1_000)
        thread = null
        return synchronized(buffer) { buffer.toShortArray().also { buffer.clear() } }
    }
}

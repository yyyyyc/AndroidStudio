package com.example.voicemimic

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit PCM WAV read/write (mono out; any channel count in). */
object Wav {
    fun write(file: File, x: FloatArray, sr: Int = Dsp.SR) {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(x.size * 2)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { it.write(h.array()); it.write(data.array()) }
        tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
    }

    /** Returns samples resampled to [Dsp.SR]. */
    fun read(file: File): FloatArray = parse(file.readBytes())

    fun parse(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var ch = 1; var sr = Dsp.SR; var bits = 16; var fmt = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = b.getInt(pos + 4)
            if (id == "fmt ") {
                fmt = b.getShort(pos + 8).toInt()
                ch = b.getShort(pos + 10).toInt()
                sr = b.getInt(pos + 12)
                bits = b.getShort(pos + 22).toInt()
            } else if (id == "data") {
                val end = minOf(bytes.size, pos + 8 + len)
                val bps = bits / 8
                val frames = (end - pos - 8) / (bps * ch)
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) {
                        val o = pos + 8 + (i * ch + c) * bps
                        s += when {
                            fmt == 3 && bits == 32 -> b.getFloat(o)
                            bits == 16 -> b.getShort(o) / 32768f
                            bits == 8 -> ((bytes[o].toInt() and 0xff) - 128) / 128f
                            bits == 24 -> ((bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8) or
                                (bytes[o + 2].toInt() shl 16)) / 8388608f
                            bits == 32 -> b.getInt(o) / 2147483648f
                            else -> 0f
                        }
                    }
                    out[i] = s / ch
                }
                return Dsp.resample(out, sr)
            }
            pos += 8 + len + (len and 1)
        }
        error("WAV has no data chunk")
    }
}

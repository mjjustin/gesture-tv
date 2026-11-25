package app.gesturetv

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Developer check: records a few seconds from the USB webcam mic to files/mic_test.wav
 * (16 kHz mono, the format speech recognizers use) and logs the level.
 * Start with: adb shell am start -n app.gesturetv/.MainActivity --ei mic_test 5
 */
object MicTest {
    fun run(ctx: Context, seconds: Int): String {
        val rate = 16000
        val am = ctx.getSystemService(AudioManager::class.java)
        val usb = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        if (usb != null) rec.preferredDevice = usb
        val pcm = ShortArray(rate * seconds)
        rec.startRecording()
        var n = 0
        while (n < pcm.size) {
            val r = rec.read(pcm, n, minOf(1600, pcm.size - n))
            if (r <= 0) break
            n += r
        }
        val routed = rec.routedDevice?.productName ?: "?"
        rec.stop(); rec.release()

        val f = File(ctx.getExternalFilesDir(null), "mic_test.wav")
        RandomAccessFile(f, "rw").use { w ->
            w.setLength(0)
            val bytes = n * 2
            fun i32(v: Int) { w.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
            fun i16(v: Int) { w.write(byteArrayOf(v.toByte(), (v shr 8).toByte())) }
            w.writeBytes("RIFF"); i32(36 + bytes); w.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1)
            i32(rate); i32(rate * 2); i16(2); i16(16); w.writeBytes("data"); i32(bytes)
            val buf = ByteArray(bytes)
            for (i in 0 until n) { buf[2 * i] = pcm[i].toInt().toByte(); buf[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
            w.write(buf)
        }
        var sum = 0.0; var peak = 0
        for (i in 0 until n) { val v = pcm[i].toInt(); sum += v * v.toDouble(); if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v) }
        val msg = "mic test: ${n / rate.toFloat()} s from '$routed' (usb found: ${usb?.productName}), rms=${sqrt(sum / maxOf(n, 1)).toInt()} peak=$peak -> ${f.path}"
        Log.i(GestureService.TAG, msg)
        return msg
    }
}

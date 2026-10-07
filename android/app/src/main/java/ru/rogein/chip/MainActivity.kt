package ru.rogein.chip

import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import ru.rogein.chip.nfc.Iso15693MemoryReader
import ru.rogein.chip.nfc.formatUid
import ru.rogein.chip.parse.BlockOrder
import ru.rogein.chip.parse.Punch
import ru.rogein.chip.parse.SfrChipMemory
import ru.rogein.chip.queue.ChipQueueRepository
import ru.rogein.chip.ui.ChipFeedback
import ru.rogein.chip.ui.ChipIo
import ru.rogein.chip.ui.ChipQueueViewModel
import ru.rogein.chip.ui.ChipScreen
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val repository by lazy { ChipQueueRepository.create(this) }
    private val viewModel: ChipQueueViewModel by viewModels {
        ChipQueueViewModel.factory(repository)
    }
    private var nfcAdapter: NfcAdapter? = null
    private val reader = Iso15693MemoryReader()
    private val nfcLock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val reading = AtomicBoolean(false)
    @Volatile
    private var sessionTag: Tag? = null
    private var misses = 0

    private val presenceTick = object : Runnable {
        override fun run() {
            val tag = sessionTag ?: return
            if (viewModel.busy.value) {
                handler.postDelayed(this, PRESENCE_MS)
                return
            }
            io.execute {
                val present = synchronized(nfcLock) {
                    if (sessionTag !== tag) return@execute
                    reader.probe(tag)
                }
                handler.post {
                    if (sessionTag !== tag) return@post
                    if (present) {
                        misses = 0
                    } else {
                        misses += 1
                    }
                    if (misses >= 2) {
                        dropChip()
                        viewModel.onChipGone()
                    } else {
                        handler.postDelayed(this, PRESENCE_MS)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        viewModel.attach(object : ChipIo {
            override fun clearMarks(order: BlockOrder, pointerEnd: Int?, blockCount: Int) {
                val tag = sessionTag ?: error("Чип убран")
                val now = LocalTime.now()
                val plan = SfrChipMemory.clearPlan(pointerEnd, now.hour, now.minute, now.second, blockCount)
                write(tag, order, plan) { done, total ->
                    runOnUiThread { viewModel.reportProgress("Очистка отметок $done/$total") }
                }
            }

            override fun writeLogicalId(order: BlockOrder, id: Int) {
                val tag = sessionTag ?: error("Чип убран")
                write(tag, order, listOf(3 to SfrChipMemory.logicalIdBlock(id))) { _, _ ->
                    runOnUiThread { viewModel.reportProgress("Запись номера…") }
                }
            }

            override fun issue(order: BlockOrder, id: Int, pointerEnd: Int?, blockCount: Int) {
                val tag = sessionTag ?: error("Чип убран")
                val now = LocalTime.now()
                val plan = SfrChipMemory.clearPlan(pointerEnd, now.hour, now.minute, now.second, blockCount) +
                    (3 to SfrChipMemory.logicalIdBlock(id))
                write(tag, order, plan) { done, total ->
                    runOnUiThread { viewModel.reportProgress("Выдача чипа $done/$total") }
                }
            }

            override fun writeTestCourse(
                order: BlockOrder,
                id: Int,
                punches: List<Punch>,
                pointerEnd: Int?,
                blockCount: Int,
            ) {
                val tag = sessionTag ?: error("Чип убран")
                val now = LocalTime.now()
                val plan = SfrChipMemory.writeCoursePlan(
                    id,
                    punches,
                    pointerEnd,
                    now.hour,
                    now.minute,
                    now.second,
                    blockCount,
                )
                write(tag, order, plan) { done, total ->
                    runOnUiThread { viewModel.reportProgress("Запись прохождения $done/$total") }
                }
            }
        })
        viewModel.attachFeedback(object : ChipFeedback {
            override fun signalSuccess() {
                runOnUiThread { announceDone(ok = true) }
            }

            override fun signalError() {
                runOnUiThread { announceDone(ok = false) }
            }
        })
        if (nfcAdapter == null) {
            viewModel.setNfcUnavailable()
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1B4D3E),
                    background = Color(0xFFF4F1EA),
                ),
            ) {
                ChipScreen(viewModel)
            }
        }
    }

    private val packTick = object : Runnable {
        override fun run() {
            viewModel.pollStartList()
            handler.postDelayed(this, PACK_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reloadStartList()
        handler.postDelayed(packTick, PACK_MS)
        nfcAdapter?.enableReaderMode(
            this,
            ::onTag,
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            bundleOf(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY to 250),
        )
    }

    override fun onPause() {
        handler.removeCallbacks(presenceTick)
        handler.removeCallbacks(packTick)
        dropChip()
        viewModel.onChipGone()
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun onTag(tag: Tag) {
        if (sessionTag?.id?.contentEquals(tag.id) == true) return
        if (!reading.compareAndSet(false, true)) return
        handler.removeCallbacks(presenceTick)
        sessionTag = null
        runOnUiThread { viewModel.markReading() }
        io.execute {
            try {
                val uid = formatUid(tag.id)
                val blocks = synchronized(nfcLock) { reader.readAllBlocks(tag) }
                sessionTag = tag
                misses = 0
                runOnUiThread {
                    viewModel.onChipBlocks(uid, blocks)
                    handler.postDelayed(presenceTick, PRESENCE_MS)
                }
            } catch (e: Exception) {
                sessionTag = null
                runOnUiThread {
                    viewModel.onReadFailed(e.message ?: "Ошибка NFC")
                }
            } finally {
                reading.set(false)
            }
        }
    }

    private fun write(
        tag: Tag,
        order: BlockOrder,
        blocks: List<Pair<Int, ByteArray>>,
        onProgress: (Int, Int) -> Unit,
    ) {
        try {
            synchronized(nfcLock) {
                reader.writeBlocks(tag, order, blocks, onProgress)
            }
        } catch (e: Exception) {
            dropChip()
            throw e
        }
    }

    private fun dropChip() {
        sessionTag = null
        misses = 0
        handler.removeCallbacks(presenceTick)
    }

    private fun announceDone(ok: Boolean) {
        try {
            val gen = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            val tone = if (ok) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK
            gen.startTone(tone, if (ok) 180 else 280)
            handler.postDelayed({ gen.release() }, 400)
        } catch (_: Exception) {
        }
        vibrate(if (ok) 80 else 140)
    }

    private fun vibrate(durationMs: Long) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        private const val PRESENCE_MS = 400L
        private const val PACK_MS = 3000L
    }
}

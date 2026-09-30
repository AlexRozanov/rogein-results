package ru.rogein.chip

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
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
import ru.rogein.chip.queue.ChipQueueRepository
import ru.rogein.chip.ui.ChipQueueViewModel
import ru.rogein.chip.ui.ChipScreen

class MainActivity : ComponentActivity() {
    private val repository by lazy { ChipQueueRepository.create(this) }
    private val viewModel: ChipQueueViewModel by viewModels {
        ChipQueueViewModel.factory(repository)
    }
    private var nfcAdapter: NfcAdapter? = null
    private val reader = Iso15693MemoryReader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
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

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this,
            ::onTag,
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            bundleOf(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY to 250),
        )
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    private fun onTag(tag: Tag) {
        runOnUiThread { viewModel.markReading() }
        try {
            val uid = formatUid(tag.id)
            val blocks = reader.readAllBlocks(tag)
            runOnUiThread {
                viewModel.onChipBlocks(uid, blocks)
                vibrate()
            }
        } catch (e: Exception) {
            runOnUiThread {
                viewModel.onReadFailed(e.message ?: "Ошибка NFC")
            }
        }
    }

    private fun vibrate() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

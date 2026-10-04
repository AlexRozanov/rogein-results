package ru.rogein.chip.parse

data class Punch(
    val cp: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    val time: String
        get() = "%02d:%02d:%02d".format(hour, minute, second)

    val isStart: Boolean get() = cp == CP_START
    val isFinish: Boolean get() = cp == CP_FINISH
}

data class ChipDump(
    val uid: String,
    val logicalId: Int?,
    val clearedAt: String?,
    val punches: List<Punch>,
    val blockOrder: BlockOrder,
    val pointerEnd: Int?,
    val blockCount: Int,
)

enum class BlockOrder {
    /** Как в USB HID SFR basic: [КП][ЧЧ][ММ][СС]. */
    Hid,
    /** Как в экспорте NFC Tools: байты блока наоборот. */
    NfcTools,
}

const val BLOCK_COUNT = 128
const val MIN_BLOCK_COUNT = 5
const val BLOCK_SIZE = 4
const val CP_FINISH = 240
const val CP_START = 241
const val CP_CLEAR = 243
const val MAX_LOGICAL_ID = 39_999

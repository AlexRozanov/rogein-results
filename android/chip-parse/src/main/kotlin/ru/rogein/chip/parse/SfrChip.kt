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

enum class StationCommand {
    Control,
    Start,
    Finish,
    Clear,
    Active,
    Passive,
    Battery,
    ;

    val controlCp: Int?
        get() = when (this) {
            Start -> CP_START
            Finish -> CP_FINISH
            Clear -> CP_CLEAR
            Control, Active, Passive, Battery -> null
        }

    val modeBlock: ByteArray
        get() = when (this) {
            Control, Start, Finish, Clear -> STATION_MODE_BLOCK
            Active -> STATION_ACTIVE_BLOCK
            Passive -> STATION_PASSIVE_BLOCK
            Battery -> STATION_BATTERY_BLOCK
        }

    companion object {
        fun fromPunchCp(cp: Int): StationCommand = when (cp) {
            CP_START -> Start
            CP_FINISH -> Finish
            CP_CLEAR -> Clear
            in 1..255 -> Control
            else -> error("номер КП станции 1…255")
        }
    }
}

data class StationRole(
    val command: StationCommand,
    val cp: Int?,
)

val STATION_MODE_BLOCK = byteArrayOf(0x00, 0x01, 0x00, 0x00)
val STATION_PARAM_BLOCK = byteArrayOf(0x00, 0x25, 0x00, 0x00)
val STATION_ACTIVE_BLOCK = byteArrayOf(0x02, 0x02, 0x00, 0x00)
val STATION_PASSIVE_BLOCK = byteArrayOf(0x03, 0x03, 0x03, 0x03)
val STATION_BATTERY_BLOCK = byteArrayOf(0x05, 0x05, 0x05, 0x05)

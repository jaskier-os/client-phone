package com.repository.navigation.model

data class NavigationInstruction(
    val type: InstructionType,
    val text: String,
    val lineName: String? = null,
    val stopName: String? = null
)

enum class InstructionType {
    WALK,
    BOARD_BUS,
    BOARD_METRO,
    BOARD_TRAM,
    BOARD_TROLLEYBUS,
    BOARD_TRAIN,
    BOARD_SUBURBAN,
    BOARD_FERRY,
    BOARD_CABLE_CAR,
    BOARD_FUNICULAR,
    BOARD_GONDOLA,
    BOARD_HIGH_SPEED_TRAIN,
    BOARD_SHARE_TAXI,
    BOARD_OTHER,
    EXIT_TRANSPORT,
    TRANSFER,
    TURN,
    DRIVE,
    CYCLE,
    ARRIVE
}

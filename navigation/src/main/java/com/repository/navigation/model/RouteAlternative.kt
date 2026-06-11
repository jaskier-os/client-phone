package com.repository.navigation.model

data class RouteAlternative(
    val alternativeId: String,
    val mode: TransportMode,
    val routeInfo: RouteInfo,
    val summary: String
)

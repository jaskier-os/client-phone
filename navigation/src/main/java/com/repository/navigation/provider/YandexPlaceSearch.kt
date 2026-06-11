package com.repository.navigation.provider

import com.repository.navigation.PlaceSearchHelper
import com.yandex.mapkit.geometry.Point
import org.json.JSONObject

/** Yandex implementation of [PlaceSearch] delegating to the existing PlaceSearchHelper object. */
class YandexPlaceSearch : PlaceSearch {
    override fun search(
        query: String,
        center: Point,
        radiusMeters: Double,
        callback: (JSONObject) -> Unit
    ) = PlaceSearchHelper.search(query, center, radiusMeters, callback)
}

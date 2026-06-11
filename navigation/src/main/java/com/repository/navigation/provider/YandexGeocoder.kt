package com.repository.navigation.provider

import com.repository.navigation.GeocoderHelper
import com.repository.navigation.ReverseGeocodeResult
import com.yandex.mapkit.geometry.Point

/** Yandex implementation of [Geocoder] delegating to the existing GeocoderHelper object. */
class YandexGeocoder : Geocoder {
    override fun geocode(address: String, callback: (Point?) -> Unit) =
        GeocoderHelper.geocode(address, callback)

    override fun reverseGeocode(point: Point, callback: (ReverseGeocodeResult?) -> Unit) =
        GeocoderHelper.reverseGeocode(point, callback)
}

package com.repository.navigation;

import com.repository.navigation.INavigationCallback;

interface INavigationService {
    void planJourney(double fromLat, double fromLng, double toLat, double toLng, INavigationCallback callback);
    void chooseMethodAndStart(String methodId, INavigationCallback callback);
    void stopJourney(INavigationCallback callback);
    void modifyJourney(double waypointLat, double waypointLng, INavigationCallback callback);
    void getCurrentEta(INavigationCallback callback);
    void getAvailableMethods(double fromLat, double fromLng, double toLat, double toLng, INavigationCallback callback);
}

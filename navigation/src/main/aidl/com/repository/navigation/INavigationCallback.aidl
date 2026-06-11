package com.repository.navigation;

import com.repository.navigation.TransportMethod;

interface INavigationCallback {
    void onJourneyPlanned(in List<TransportMethod> methods);
    void onJourneyStarted(long sessionId, long etaSeconds);
    void onJourneyStopped();
    void onJourneyModified(long newEtaSeconds);
    void onEtaResult(long etaSeconds, String etaFormatted);
    void onError(String errorMessage);
}

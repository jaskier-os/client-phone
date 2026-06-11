package com.repository.navigation

import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AidlServiceTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            NavigationModule.init(context)
        }
    }

    private fun bindService(): INavigationService {
        val intent = Intent("com.repository.navigation.BIND").apply {
            setClassName(
                ApplicationProvider.getApplicationContext<Context>().packageName,
                "com.repository.navigation.NavigationService"
            )
        }
        val binder: IBinder = serviceRule.bindService(intent)
        return INavigationService.Stub.asInterface(binder)
    }

    @Test
    fun serviceIsBindable() {
        val service = bindService()
        assertNotNull(service)
    }

    @Test
    fun getCurrentEtaReturnsZeroWithNoSession() {
        val service = bindService()
        val latch = CountDownLatch(1)
        var receivedEta = -1L

        service.getCurrentEta(object : INavigationCallback.Stub() {
            override fun onJourneyPlanned(methods: MutableList<TransportMethod>?) {}
            override fun onJourneyStarted(sessionId: Long, etaSeconds: Long) {}
            override fun onJourneyStopped() {}
            override fun onJourneyModified(newEtaSeconds: Long) {}
            override fun onEtaResult(etaSeconds: Long, etaFormatted: String?) {
                receivedEta = etaSeconds
                latch.countDown()
            }
            override fun onError(errorMessage: String?) {
                latch.countDown()
            }
        })

        latch.await(5, TimeUnit.SECONDS)
        assert(receivedEta == 0L) { "Expected ETA 0 with no active session, got $receivedEta" }
    }

    @Test
    fun stopJourneySucceedsWithNoSession() {
        val service = bindService()
        val latch = CountDownLatch(1)
        var stopped = false

        service.stopJourney(object : INavigationCallback.Stub() {
            override fun onJourneyPlanned(methods: MutableList<TransportMethod>?) {}
            override fun onJourneyStarted(sessionId: Long, etaSeconds: Long) {}
            override fun onJourneyStopped() {
                stopped = true
                latch.countDown()
            }
            override fun onJourneyModified(newEtaSeconds: Long) {}
            override fun onEtaResult(etaSeconds: Long, etaFormatted: String?) {}
            override fun onError(errorMessage: String?) {
                latch.countDown()
            }
        })

        latch.await(5, TimeUnit.SECONDS)
        assert(stopped) { "Expected onJourneyStopped callback" }
    }
}

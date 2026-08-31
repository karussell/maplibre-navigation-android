package org.maplibre.navigation.core.android.location.engine

import android.content.Context
import android.location.Criteria
import android.location.Location as AndroidLocation
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.maplibre.navigation.core.location.engine.LocationEngine
import org.maplibre.navigation.core.location.engine.MapLibreLocationEngine
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MapLibreLocationEngineTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun engine() = MapLibreLocationEngine(context, Looper.getMainLooper())

    private fun highAccuracyRequest() = LocationEngine.Request(
        accuracy = LocationEngine.Request.Accuracy.HIGH,
        minUpdateDistanceMeters = 0f,
        intervalMilliseconds = 1000,
    )

    private fun ensureFusedProvider() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
        ) {
            shadowOf(locationManager).setProviderProperties(
                LocationManager.FUSED_PROVIDER,
                ShadowLocationManager.ProviderProperties(
                    false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
                ),
            )
        }
    }

    private fun collectFirstLocation(request: LocationEngine.Request, provider: String): org.maplibre.navigation.core.location.Location =
        runBlocking {
            val firstLocation = async(start = CoroutineStart.UNDISPATCHED) {
                engine().listenToLocation(request).first()
            }
            // The callbackFlow producer is a dispatched child coroutine: yield so it runs and
            // registers its listener before a location fix is simulated
            yield()
            assertTrue(shadowOf(locationManager).locationUpdateListeners.isNotEmpty())

            shadowOf(locationManager).simulateLocation(
                AndroidLocation(provider).apply {
                    latitude = 1.0
                    longitude = 2.0
                    time = 3
                }
            )
            shadowOf(Looper.getMainLooper()).idle()

            withTimeout(5_000) { firstLocation.await() }
        }

    @Test
    @Config(sdk = [34])
    fun `subscribes fused provider on Android 12+`() {
        ensureFusedProvider()

        val location = collectFirstLocation(highAccuracyRequest(), LocationManager.FUSED_PROVIDER)

        assertEquals(1.0, location.latitude)
        assertEquals(2.0, location.longitude)
        assertEquals(LocationManager.FUSED_PROVIDER, location.provider)
    }

    @Test
    @Config(sdk = [28])
    fun `subscribes GPS provider below Android 12 for high accuracy`() {
        val location = collectFirstLocation(highAccuracyRequest(), LocationManager.GPS_PROVIDER)

        assertEquals(1.0, location.latitude)
        assertEquals(2.0, location.longitude)
        assertEquals(LocationManager.GPS_PROVIDER, location.provider)
    }

    @Test
    @Config(sdk = [34])
    fun `stops listening when flow collection is cancelled`() {
        ensureFusedProvider()

        collectFirstLocation(highAccuracyRequest(), LocationManager.FUSED_PROVIDER)

        assertEquals(emptyList(), shadowOf(locationManager).locationUpdateListeners)
    }

    @Test
    @Config(sdk = [34])
    fun `last location is most recent fix across providers`() = runBlocking {
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            AndroidLocation(LocationManager.NETWORK_PROVIDER).apply {
                latitude = 1.0
                longitude = 2.0
                time = 2000
            }
        )
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            AndroidLocation(LocationManager.GPS_PROVIDER).apply {
                latitude = 3.0
                longitude = 4.0
                time = 1000
            }
        )

        val lastLocation = engine().getLastLocation()

        assertEquals(1.0, lastLocation?.latitude)
        assertEquals(2.0, lastLocation?.longitude)
        assertEquals(LocationManager.NETWORK_PROVIDER, lastLocation?.provider)
    }

    @Test
    @Config(sdk = [34])
    fun `last location is null without any known location`() = runBlocking {
        assertNull(engine().getLastLocation())
    }
}

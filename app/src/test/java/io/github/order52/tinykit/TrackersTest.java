package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TrackersTest {
    @Test
    public void knownLibrariesAreNamed() {
        assertEquals("Google Analytics", Trackers.find("com.google.firebase.analytics.FirebaseAnalytics"));
        assertEquals("AppLovin", Trackers.find("com.applovin.adview.AppLovinFullscreenActivity"));
        assertEquals("Braze", Trackers.find("com.braze.push.BrazeFirebaseMessagingService"));
    }

    @Test
    public void appsOwnClassesAreNotTrackers() {
        assertNull(Trackers.find("com.example.app.MainActivity"));
        assertNull(Trackers.find("com.google.firebase.messaging.FirebaseMessagingService"));
    }
}

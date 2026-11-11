package io.github.archonw.starswipe.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNetworkTest {
    @Test fun homeHostsAllowed() = listOf("192.168.178.20", "10.0.0.1", "172.20.1.1", "nas.local", "fritz.box", "x.fritz.box")
        .forEach { assertTrue(it, HomeNetwork.allowsCleartext(it)) }
    @Test fun publicHostsRejected() = listOf("8.8.8.8", "cloud.example.org", "172.32.0.1", "evilfritz.box", "192.168.1.1.example.com")
        .forEach { assertFalse(it, HomeNetwork.allowsCleartext(it)) }
}

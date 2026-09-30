package org.nomad.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCryptoTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun selfTestPassesOnThisDevice() {
        val dir = File(context.cacheDir, "it-${System.nanoTime()}")
        val steps = SelfTest.runAll(dir) + SelfTest.keystoreStep(context)
        dir.deleteRecursively()
        val failed = steps.filter { !it.ok }
        assertTrue("failed steps: $failed", failed.isEmpty())
    }

    @Test
    fun identityIsTheSameAfterTheRepositoryIsOpenedAgain() {
        val first = StateRepository.forContext(context)
        val uid = first.state.identity.uid()
        val second = StateRepository.forContext(context)
        assertEquals(uid, second.state.identity.uid())
        assertTrue(second.loadedFromDisk)
    }
}

package me.rerere.rikkahub.device

import org.junit.Assert.assertNotNull
import org.junit.Test

class RootDeviceCommandRunnerTest {
    @Test fun runnerUsesStandardSuEntryPoint() {
        assertNotNull(RootDeviceCommandRunner("su"))
    }
}

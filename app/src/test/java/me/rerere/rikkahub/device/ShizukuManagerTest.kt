package me.rerere.rikkahub.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuManagerTest {
    @Test
    fun `already connected and authorized backend is recognized on creation`() {
        val backend = FakeBackend().apply { granted = true }
        ShizukuManager(backend).use { manager ->
            assertEquals(ShizukuState.AUTHORIZED, manager.state.value)
            manager.requestPermission(1001)
            assertTrue(backend.requests.isEmpty())
        }
    }

    @Test
    fun `request without binder shows how to start Shizuku`() {
        val backend = FakeBackend().apply { alive = false }
        ShizukuManager(backend).use { manager ->
            manager.requestPermission(1001)
            assertEquals(ShizukuState.UNAVAILABLE, manager.state.value)
            assertTrue(manager.message.value.orEmpty().contains("启动服务"))
            assertTrue(backend.requests.isEmpty())
        }
    }

    @Test
    fun `binder arrival allows a subsequent user requested permission dialog`() {
        val backend = FakeBackend().apply { alive = false }
        ShizukuManager(backend).use { manager ->
            backend.alive = true
            backend.binderReceived()
            assertEquals(ShizukuState.UNAUTHORIZED, manager.state.value)
            assertTrue(backend.requests.isEmpty())
            manager.requestPermission(1001)
            assertEquals(listOf(1001), backend.requests)
        }
    }

    @Test
    fun `permanent denial directs user to manager instead of silently requesting again`() {
        val backend = FakeBackend().apply { deniedPermanently = true }
        ShizukuManager(backend).use { manager ->
            manager.requestPermission(1001)
            assertEquals(ShizukuState.DENIED, manager.state.value)
            assertTrue(manager.message.value.orEmpty().contains("应用管理"))
            assertTrue(backend.requests.isEmpty())
        }
    }

    @Test
    fun `old service is detected before using unsupported permission API`() {
        val backend = FakeBackend().apply { preV11 = true }
        ShizukuManager(backend).use { manager ->
            manager.requestPermission(1001)
            assertEquals(ShizukuState.UNSUPPORTED, manager.state.value)
            assertEquals(0, backend.permissionChecks)
            assertTrue(backend.requests.isEmpty())
        }
    }

    @Test
    fun `permission request exception is visible and can be retried`() {
        val backend = FakeBackend().apply { requestError = SecurityException("permission service unavailable") }
        ShizukuManager(backend).use { manager ->
            manager.requestPermission(1001)
            assertEquals(ShizukuState.ERROR, manager.state.value)
            assertTrue(manager.message.value.orEmpty().contains("permission service unavailable"))
            backend.requestError = null
            manager.requestPermission(1001)
            backend.permissionResult(1001, true)
            assertEquals(ShizukuState.AUTHORIZED, manager.state.value)
        }
    }

    @Test
    fun `grant callback overrides an older permission snapshot`() {
        val backend = FakeBackend()
        ShizukuManager(backend).use { manager ->
            manager.requestPermission(1001)
            // The result itself is authoritative, even before another permission query updates.
            backend.permissionResult(1001, true)
            assertEquals(ShizukuState.AUTHORIZED, manager.state.value)
            assertTrue(manager.message.value.orEmpty().contains("授权成功"))
        }
    }

    @Test
    fun `immediate grant during request is not overwritten by waiting message`() {
        val backend = FakeBackend().apply { immediateResult = true }
        ShizukuManager(backend).use { manager ->
            manager.requestPermission(1001)
            assertEquals(ShizukuState.AUTHORIZED, manager.state.value)
            assertTrue(manager.message.value.orEmpty().contains("授权成功"))
        }
    }

    @Test
    fun `ordinary denial remains retryable and displays a result`() {
        val backend = FakeBackend()
        ShizukuManager(backend).use { manager ->
            manager.requestPermission(1001)
            backend.permissionResult(1001, false)
            assertEquals(ShizukuState.UNAUTHORIZED, manager.state.value)
            assertTrue(manager.message.value.orEmpty().contains("被拒绝"))
            manager.requestPermission(1002)
            assertEquals(listOf(1001, 1002), backend.requests)
        }
    }

    @Test
    fun `unrelated permission results do not authorize current request`() {
        val backend = FakeBackend()
        ShizukuManager(backend).use { manager ->
            manager.requestPermission(1001)
            backend.permissionResult(999, true)
            assertEquals(ShizukuState.UNAUTHORIZED, manager.state.value)
            backend.permissionResult(1001, true)
            assertEquals(ShizukuState.AUTHORIZED, manager.state.value)
        }
    }

    @Test
    fun `manual authorization and service restart refresh the displayed state`() {
        val backend = FakeBackend()
        ShizukuManager(backend).use { manager ->
            backend.granted = true
            manager.refresh()
            assertEquals(ShizukuState.AUTHORIZED, manager.state.value)
            assertNull(manager.message.value)
            backend.alive = false
            backend.binderDead()
            assertEquals(ShizukuState.UNAVAILABLE, manager.state.value)
            assertNotNull(manager.message.value)
            backend.alive = true
            backend.granted = false
            backend.binderReceived()
            assertEquals(ShizukuState.UNAUTHORIZED, manager.state.value)
        }
    }

    @Test
    fun `closing unregisters listeners once and ignores late callbacks`() {
        val backend = FakeBackend()
        val manager = ShizukuManager(backend)
        manager.close()
        manager.close()
        backend.granted = true
        backend.binderReceived()
        manager.requestPermission(1001)
        assertEquals(1, backend.closeCalls)
        assertTrue(backend.requests.isEmpty())
        assertEquals(ShizukuState.UNAUTHORIZED, manager.state.value)
    }

    private class FakeBackend : ShizukuPermissionBackend {
        var alive = true
        var preV11 = false
        var granted = false
        var deniedPermanently = false
        var requestError: Exception? = null
        var immediateResult: Boolean? = null
        var closeCalls = 0
        var permissionChecks = 0
        val requests = mutableListOf<Int>()
        lateinit var binderReceived: () -> Unit
        lateinit var binderDead: () -> Unit
        lateinit var permissionResult: (Int, Boolean) -> Unit

        override fun isBinderAlive() = alive
        override fun isPreV11() = preV11
        override fun isPermissionGranted(): Boolean {
            permissionChecks++
            return granted
        }
        override fun shouldShowRequestPermissionRationale() = deniedPermanently
        override fun requestPermission(requestCode: Int) {
            requestError?.let { throw it }
            requests += requestCode
            immediateResult?.let { permissionResult(requestCode, it) }
        }

        override fun observe(
            onBinderReceived: () -> Unit,
            onBinderDead: () -> Unit,
            onPermissionResult: (Int, Boolean) -> Unit,
        ): AutoCloseable {
            binderReceived = onBinderReceived
            binderDead = onBinderDead
            permissionResult = onPermissionResult
            if (alive) onBinderReceived()
            return AutoCloseable { closeCalls++ }
        }
    }
}

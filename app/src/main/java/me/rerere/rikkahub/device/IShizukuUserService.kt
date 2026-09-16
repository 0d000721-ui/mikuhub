package me.rerere.rikkahub.device

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import android.os.ParcelFileDescriptor

interface IShizukuUserService : IInterface {
    fun exec(command: String, cwd: String): DeviceShellResult
    fun installApk(source: ParcelFileDescriptor, size: Long, userId: Int): DeviceShellResult
    fun cancel()
    fun destroy()

    abstract class Stub : Binder(), IShizukuUserService {
        init { attachInterface(this, DESCRIPTOR) }
        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
            TRANSACTION_INSTALL_APK -> {
                data.enforceInterface(DESCRIPTOR)
                val source = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                val result = source.use { installApk(it, data.readLong(), data.readInt()) }
                requireNotNull(reply).apply {
                    writeNoException()
                    writeInt(result.exitCode)
                    writeString(result.output)
                    writeInt(if (result.timedOut) 1 else 0)
                    writeInt(if (result.cancelled) 1 else 0)
                    writeInt(if (result.truncated) 1 else 0)
                }
                true
            }
            TRANSACTION_EXEC -> {
                data.enforceInterface(DESCRIPTOR)
                val response = requireNotNull(reply)
                val result = exec(data.readString().orEmpty(), data.readString() ?: "/")
                response.apply {
                    writeNoException()
                    writeInt(result.exitCode)
                    writeString(result.output)
                    writeInt(if (result.timedOut) 1 else 0)
                    writeInt(if (result.cancelled) 1 else 0)
                    writeInt(if (result.truncated) 1 else 0)
                }
                true
            }
            TRANSACTION_CANCEL -> { data.enforceInterface(DESCRIPTOR); cancel(); true }
            TRANSACTION_DESTROY -> { data.enforceInterface(DESCRIPTOR); destroy(); true }
            INTERFACE_TRANSACTION -> { reply?.writeString(DESCRIPTOR); true }
            else -> super.onTransact(code, data, reply, flags)
        }

        companion object {
            const val DESCRIPTOR = "me.rerere.rikkahub.device.IShizukuUserService"
            const val TRANSACTION_EXEC = 1
            const val TRANSACTION_CANCEL = 2
            const val TRANSACTION_INSTALL_APK = 3
            const val TRANSACTION_DESTROY = 16777115

            fun asInterface(binder: IBinder): IShizukuUserService {
                (binder.queryLocalInterface(DESCRIPTOR) as? IShizukuUserService)?.let { return it }
                return object : IShizukuUserService {
                    override fun asBinder() = binder
                    override fun installApk(source: ParcelFileDescriptor, size: Long, userId: Int): DeviceShellResult {
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        return try {
                            data.writeInterfaceToken(DESCRIPTOR)
                            source.writeToParcel(data, 0)
                            data.writeLong(size)
                            data.writeInt(userId)
                            if (!binder.transact(TRANSACTION_INSTALL_APK, data, reply, 0)) {
                                throw RemoteException("安装服务版本过旧，请重启 Shizuku 后重试")
                            }
                            reply.readException()
                            DeviceShellResult(reply.readInt(), reply.readString().orEmpty(), reply.readInt() != 0,
                                reply.readInt() != 0, reply.readInt() != 0)
                        } finally { data.recycle(); reply.recycle() }
                    }
                    override fun exec(command: String, cwd: String): DeviceShellResult {
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        return try {
                            data.writeInterfaceToken(DESCRIPTOR)
                            data.writeString(command)
                            data.writeString(cwd)
                            if (!binder.transact(TRANSACTION_EXEC, data, reply, 0)) throw RemoteException("Shizuku command service is incompatible")
                            reply.readException()
                            DeviceShellResult(reply.readInt(), reply.readString().orEmpty(), reply.readInt() != 0,
                                reply.readInt() != 0, reply.readInt() != 0)
                        } finally {
                            data.recycle()
                            reply.recycle()
                        }
                    }

                    override fun cancel() = sendOneWay(binder, TRANSACTION_CANCEL)
                    override fun destroy() = sendOneWay(binder, TRANSACTION_DESTROY)
                }
            }

            private fun sendOneWay(binder: IBinder, code: Int) {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    if (!binder.transact(code, data, null, IBinder.FLAG_ONEWAY)) throw RemoteException("Shizuku command service is unavailable")
                } finally {
                    data.recycle()
                }
            }
        }
    }
}

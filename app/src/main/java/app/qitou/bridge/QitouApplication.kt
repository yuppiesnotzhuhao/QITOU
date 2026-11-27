package app.qitou.bridge

import android.app.Application

class QitouApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.clearStale(this)
        CrashLogger.install(this)
        MiniState.attach(this)
    }
}

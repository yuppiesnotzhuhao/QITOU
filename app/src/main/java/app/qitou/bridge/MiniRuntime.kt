package app.qitou.bridge

import app.qitou.bridge.carplus.CarLifeReceiver

/** Process-local handles shared by the foreground service and the control Activity. */
object MiniRuntime {
    @Volatile var pipeline: MiniVideoPipeline? = null
    @Volatile var receiver: CarLifeReceiver? = null
}

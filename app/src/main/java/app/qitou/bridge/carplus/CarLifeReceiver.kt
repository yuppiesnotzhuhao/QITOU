// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.carplus

interface CarLifeReceiver {
    fun start()
    fun stop()
    fun sendTouch(action: Int, x: Int, y: Int)
    fun sendTouchComposite(actionCode: Int, x: Int, y: Int)
}

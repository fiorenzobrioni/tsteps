package com.callbackdev.tsteps

import android.app.Application
import com.callbackdev.tsteps.widget.ScreenWakeRefresh

/**
 * The one thing tsteps needs a process-wide hook for: the widget's screen-wake
 * refresh (Fase 25).
 *
 * `ACTION_SCREEN_ON` only exists as a runtime registration, and a runtime
 * registration lives and dies with the process. Every other reconciliation in
 * this app hangs off the activity or a widget broadcast, and both of those leave
 * a process that was started by a worker — the ordinary case, a phone whose owner
 * has not opened tsteps today — with nobody to arm it. Process start is the
 * moment that covers all of them, because the sampler, the widget broadcasts, the
 * services and the app itself all come through here.
 *
 * Off the main thread on purpose: arming reads the permission, the sensor list
 * and the widget ids, and the first frame is not the place to wait for three
 * system calls. There is nothing else here — no eager graph, no warm-up: the
 * `ServiceLocator` is lazy by design and must stay that way, since this runs
 * before every worker too, not just before the UI.
 */
class TstepsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ScreenWakeRefresh.reconcileDetached(this)
    }
}

package com.youreyes.app

import android.app.Application
import com.youreyes.app.telephony.IncomingCallMonitor

class YourEyesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        IncomingCallMonitor.start(this)
    }
}

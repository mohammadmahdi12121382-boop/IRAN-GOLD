package ir.irangold.app

import android.app.Application

class GoldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Gold.init(this)
    }
}

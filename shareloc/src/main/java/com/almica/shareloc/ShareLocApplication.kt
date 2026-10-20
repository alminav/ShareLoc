package com.almica.shareloc

import android.app.Application
import timber.log.Timber.DebugTree

class ShareLocApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            timber.log.Timber.plant(object : DebugTree() {
                override fun createStackElementTag(element: StackTraceElement): String? {
                    return "${super.createStackElementTag(element)}.${element.methodName}:${element.lineNumber}"
                }

                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    val stackTrace = Throwable().stackTrace
                    val caller = stackTrace.firstOrNull { element ->
                        !element.className.startsWith("timber.log.Timber") &&
                                !element.className.startsWith(ShareLocApplication::class.java.name)
                    }
                    val formattedMessage = if (caller != null) {
                        "(${caller.fileName}:${caller.lineNumber})#${caller.methodName}: $message"
                    } else {
                        message
                    }
                    super.log(priority, tag, formattedMessage, t)
                }
            })
        }
    }
}

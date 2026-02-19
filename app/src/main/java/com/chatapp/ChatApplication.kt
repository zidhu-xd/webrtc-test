package com.chatapp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class - required for Hilt dependency injection.
 * Hilt uses this to generate its component graph at compile time.
 */
@HiltAndroidApp
class ChatApplication : Application()

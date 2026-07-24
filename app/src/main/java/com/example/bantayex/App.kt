package com.example.bantayex

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. @HiltAndroidApp triggers Hilt's code generation
 * and creates the app-level dependency container that everything else hangs off.
 *
 * Remember to register this in AndroidManifest.xml:
 *   <application android:name=".App" ... >
 */
@HiltAndroidApp
class App : Application()
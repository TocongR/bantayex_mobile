package com.example.bantayex.core.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * This is the ONLY place `FirebaseAuth.getInstance()` / `FirebaseFirestore.getInstance()`
 * should ever be called in the whole app. Everything else (SessionManager, Repositories)
 * gets these injected via the constructor.
 *
 * Why this matters: in the old codebase, `FirebaseAuth.getInstance()` was called directly
 * inside LoginViewModel and `ExamRepository()` was `new`'d up by hand inside other
 * ViewModels. That's fine at 3 files; at 15+ files it means every class quietly
 * re-implements "how do I get Firestore" and there's no single seam to intercept
 * (e.g. for a future emulator/test config).
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()
}
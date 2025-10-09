package com.example.llama

import android.app.Application
import android.llama.cpp.LLamaAndroid
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application, LLamaAndroid.Companion.instance()) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

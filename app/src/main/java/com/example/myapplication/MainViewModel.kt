package com.example.myapplication

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {

    private val _greetingText = MutableLiveData("Hello Android!")
    val greetingText: LiveData<String> = _greetingText

    private var clickCount = 0

    fun onButtonClicked() {
        clickCount++
        _greetingText.value = "Hello Android! Clicked $clickCount times"
    }
}

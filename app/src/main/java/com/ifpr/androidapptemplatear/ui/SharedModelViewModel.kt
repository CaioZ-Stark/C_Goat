package com.ifpr.androidapptemplatear.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedModelViewModel : ViewModel() {
    val selectedModel = MutableLiveData<String>()

    fun selectModel(modelPath: String) {
        selectedModel.value = modelPath
    }
}

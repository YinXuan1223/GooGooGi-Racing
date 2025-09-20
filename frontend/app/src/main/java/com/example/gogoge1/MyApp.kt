package com.example.gogoge1

import android.app.Application
import androidx.lifecycle.MutableLiveData

class MyApp : Application(){
    var globalState = MutableLiveData<Int>().apply { value = 0 }
}
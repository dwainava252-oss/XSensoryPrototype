package com.k2e7.xsensory

import androidx.annotation.Nullable
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.k2e7.xsensory.helpers.FileExtractor
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel : ViewModel() {

    val fileLiveData : MutableLiveData<File?> = MutableLiveData()
    fun getNthFile(n:Int){
        viewModelScope.launch {
            fileLiveData.postValue(FileExtractor.getNthVideoFile(n))
        }
    }

}
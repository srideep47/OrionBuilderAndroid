package com.legion.orion.feature.preview

import androidx.lifecycle.ViewModel
import com.legion.orion.runtime.DevServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    val previewController: PreviewController,
    devServerState: DevServerState,
) : ViewModel() {
    val devServer = devServerState.current
}

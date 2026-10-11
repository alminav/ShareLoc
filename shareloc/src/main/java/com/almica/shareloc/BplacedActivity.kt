package com.almica.shareloc

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import timber.log.Timber

class BplacedActivity : AppCompatActivity() {

    private val viewModel: BplacedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        observeViewModel()

        // Example call
        viewModel.sendLocation("Mein Android Standort", 52.3274, 10.3042, altitude = 72.0)
        Timber.i("BplacedActivity sendLocation requested")
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is LocationUiState.Success -> {
                            Toast.makeText(this@BplacedActivity, state.message, Toast.LENGTH_LONG).show()
                        }
                        is LocationUiState.Error -> {
                            Toast.makeText(this@BplacedActivity, state.message, Toast.LENGTH_LONG).show()
                        }
                        LocationUiState.Loading, LocationUiState.Idle -> {
                            // Can be used for progress indicators if UI is added
                        }
                    }
                }
            }
        }
    }
}

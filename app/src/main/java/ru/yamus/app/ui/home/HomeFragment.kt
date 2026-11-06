package ru.yamus.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.data.AccountStore
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.player.WaveController
import ru.yamus.app.core.util.artistText
import ru.yamus.app.core.util.avatarUrl
import ru.yamus.app.core.util.displayName
import ru.yamus.app.core.util.displayTitle
import ru.yamus.app.core.util.dp
import ru.yamus.app.ui.common.setCircleBackground

class HomeFragment : Fragment(), PlaybackManager.Listener {

    private lateinit var avatar: ImageView
    private lateinit var accountName: TextView
    private lateinit var accountEmail: TextView
    private lateinit var waveState: TextView
    private lateinit var waveButton: View
    private lateinit var waveButtonText: TextView
    private lateinit var waveSeedBox: View
    private lateinit var waveSeedText: TextView
    private lateinit var waveNormalButton: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        avatar = view.findViewById(R.id.avatar)
        avatar.setCircleBackground(requireContext(), R.attr.yamusCardElevated)
        accountName = view.findViewById(R.id.accountName)
        accountEmail = view.findViewById(R.id.accountEmail)
        waveState = view.findViewById(R.id.waveState)
        waveButton = view.findViewById(R.id.waveButton)
        waveButtonText = view.findViewById(R.id.waveButtonText)
        waveSeedBox = view.findViewById(R.id.waveSeedBox)
        waveSeedText = view.findViewById(R.id.waveSeedText)
        waveNormalButton = view.findViewById(R.id.waveNormalButton)

        waveButton.setOnClickListener {
            if (PlaybackManager.waveActive) {
                WaveController.stop()
                PlaybackManager.toast("Волна выключена")
            } else {
                waveButton.isEnabled = false
                waveButtonText.text = getString(R.string.wave_loading)
                waveState.text = getString(R.string.wave_loading)
                WaveController.start()
            }
            updateWaveUi()
        }

        waveNormalButton.setOnClickListener {
            waveNormalButton.isEnabled = false
            waveButton.isEnabled = false
            waveButtonText.text = getString(R.string.wave_loading)
            waveState.text = getString(R.string.wave_loading)
            WaveController.startNormal()
            updateWaveUi()
        }

        updateWaveUi()
        loadAccount()
    }

    override fun onStart() {
        super.onStart()
        PlaybackManager.addListener(this)
        updateWaveUi()
    }

    override fun onStop() {
        PlaybackManager.removeListener(this)
        super.onStop()
    }

    override fun onPlaybackChanged() {
        if (!isAdded) return
        activity?.runOnUiThread { updateWaveUi() }
    }

    private fun updateWaveUi() {
        if (!isAdded) return
        val active = PlaybackManager.waveActive
        val seed = WaveController.seedTrack
        val loading = WaveController.isLoading || (active && PlaybackManager.isPreparing)
        val trackRadio = seed != null && (active || WaveController.isLoading)

        if (trackRadio) {
            waveSeedBox.visibility = View.VISIBLE
            waveSeedText.text = getString(
                R.string.wave_seed,
                seed?.displayTitle().orEmpty(),
                seed?.artistText().orEmpty()
            )
            waveNormalButton.visibility = View.VISIBLE
            waveNormalButton.isEnabled = !loading
            waveButton.isEnabled = !loading
            if (loading) {
                waveState.text = getString(R.string.wave_loading)
                waveButtonText.text = getString(R.string.wave_loading)
            } else {
                waveState.text = getString(R.string.wave_track_state)
                waveButtonText.text = getString(R.string.wave_stop)
            }
            return
        }

        waveSeedBox.visibility = View.GONE
        waveNormalButton.visibility = View.GONE

        when {
            loading -> {
                waveState.text = getString(R.string.wave_loading)
                waveButtonText.text = getString(R.string.wave_loading)
                waveButton.isEnabled = false
            }
            active -> {
                waveState.text = getString(R.string.wave_on)
                waveButtonText.text = getString(R.string.wave_stop)
                waveButton.isEnabled = true
            }
            else -> {
                waveState.text = getString(R.string.wave_off)
                waveButtonText.text = getString(R.string.wave_off)
                waveButton.isEnabled = true
            }
        }
    }

    private fun loadAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            val account = AccountStore.load()
            if (!isAdded) return@launch
            if (account == null) {
                accountName.text = "Не удалось загрузить аккаунт"
                accountEmail.text = ""
                return@launch
            }
            accountName.text = account.displayName()
            accountEmail.text = account.email.orEmpty()

            // У Яндекс-аватаров доступны только фиксированные размеры
            // (50/150/200/300 и т.д.), 240 отдаёт 404.
            val url = account.avatarUrl(300)
            if (url.isNullOrBlank()) {
                avatar.setPadding(requireContext().dp(16), requireContext().dp(16), requireContext().dp(16), requireContext().dp(16))
                avatar.scaleType = ImageView.ScaleType.FIT_CENTER
                avatar.setImageDrawable(
                    AppCompatResources.getDrawable(requireContext(), R.drawable.ic_person)
                )
            } else {
                avatar.setPadding(0, 0, 0, 0)
                avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(this@HomeFragment)
                    .load(url)
                    .apply(RequestOptions.circleCropTransform())
                    .into(avatar)
            }
        }
    }
}

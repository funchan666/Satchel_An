package com.satchel.traveljournal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

class PortalAds(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val consent = UserMessagingPlatform.getConsentInformation(activity)
    var privacyRequired by mutableStateOf(false); private set
    var consentInProgress = false; private set
    var presenting = false; private set
    private var active = false
    private var generation = 0
    private var completion: ((String) -> Unit)? = null
    private var sdkStarted = false
    private fun updatePrivacy() { privacyRequired = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED }
    fun privacyOptions() { if (privacyRequired) UserMessagingPlatform.showPrivacyOptionsForm(activity) { updatePrivacy() } }
    fun cancelOpen() { if (active && !consentInProgress && !presenting) finish("ad_not_ready") }
    private fun finish(result: String) {
        val callback = completion ?: return
        generation++; completion = null; active = false; presenting = false; consentInProgress = false
        callback(result)
    }
    fun show(kind: String, callback: (String) -> Unit) {
        if (active) { callback("ad_busy"); return }
        if (activity.isFinishing || activity.isDestroyed) { callback("ad_not_ready"); return }
        active = true; completion = callback; val attempt = ++generation
        consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            if (attempt != generation) return@requestConsentInfoUpdate
            updatePrivacy(); consentInProgress = true
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                consentInProgress = false; updatePrivacy()
                if (attempt != generation) return@loadAndShowConsentFormIfRequired
                if (error != null || !consent.canRequestAds()) finish("ad_not_ready") else initialize(attempt, kind)
            }
        }, {
            updatePrivacy()
            if (attempt == generation) { if (consent.canRequestAds()) initialize(attempt, kind) else finish("ad_not_ready") }
        })
        if (kind != "open") handler.postDelayed({ if (attempt == generation && !consentInProgress && !presenting) finish("ad_load_failed") }, 30_000)
    }
    private fun initialize(attempt: Int, kind: String) {
        if (attempt != generation) return
        if (sdkStarted) { load(attempt, kind); return }
        MobileAds.initialize(activity) { handler.post { if (attempt == generation) { sdkStarted = true; load(attempt, kind) } } }
    }
    private fun contentCallback(attempt: Int, result: () -> String) = object : FullScreenContentCallback() {
        override fun onAdDismissedFullScreenContent() { if (attempt == generation) finish(result()) }
        override fun onAdFailedToShowFullScreenContent(error: AdError) { if (attempt == generation) finish("ad_show_failed") }
    }
    private fun canShow(attempt: Int): Boolean = attempt == generation && !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()
    private fun load(attempt: Int, kind: String) {
        val request = AdRequest.Builder().build()
        when (kind) {
            "open" -> AppOpenAd.load(activity, "ca-app-pub-3940256099942544/9257395921", request, object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) { if (!canShow(attempt)) { if (attempt == generation) finish("ad_not_ready"); return }; presenting = true; ad.fullScreenContentCallback = contentCallback(attempt) { "dismissed" }; ad.show(activity) }
                override fun onAdFailedToLoad(error: LoadAdError) { if (attempt == generation) finish("ad_load_failed") }
            })
            "interstitial" -> InterstitialAd.load(activity, "ca-app-pub-3940256099942544/1033173712", request, object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { if (!canShow(attempt)) { if (attempt == generation) finish("ad_not_ready"); return }; presenting = true; ad.fullScreenContentCallback = contentCallback(attempt) { "dismissed" }; ad.show(activity) }
                override fun onAdFailedToLoad(error: LoadAdError) { if (attempt == generation) finish("ad_load_failed") }
            })
            "rewarded" -> RewardedAd.load(activity, "ca-app-pub-3940256099942544/5224354917", request, object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    if (!canShow(attempt)) { if (attempt == generation) finish("ad_not_ready"); return }
                    var earned = false; presenting = true; ad.fullScreenContentCallback = contentCallback(attempt) { if (earned) "rewarded" else "dismissed" }
                    ad.show(activity) { earned = true }
                }
                override fun onAdFailedToLoad(error: LoadAdError) { if (attempt == generation) finish("ad_load_failed") }
            })
            else -> finish("ad_not_ready")
        }
    }
}

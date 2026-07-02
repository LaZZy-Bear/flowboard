---
name: billing-ads-integration
description: Standardized patterns for integrating Google Play Billing Library (v6+) for in-app purchases (skins, premium features) and Google Mobile Ads SDK (AdMob) for banner/interstitial advertisements within an IME keyboard lifecycle.
---

# Google Play Billing & Mobile Ads Integration Skill

This skill outlines guidelines and architecture patterns for embedding billing APIs (for selling custom themes, emojis, features) and AdMob ads inside the Flowboard IME.

## 1. Google Play Billing Library Integration (v6+)

Handle standard billing initialization, product fetching, and purchasing safely.

### Billing Client Manager
Create a singleton class or dedicated helper class to wrap Billing operations:

```kotlin
import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*

class BillingManager(context: Context) : PurchasesUpdatedListener {

    private val billingClient = BillingClient.newBuilder(context)
        .setChildDirectedTreatment(BillingClient.ChildDirectedTreatment.CHILD_DIRECTED_UNSPECIFIED)
        .setUnderAgeOfConsent(BillingClient.UnderAgeOfConsent.UNDER_AGE_OF_CONSENT_UNSPECIFIED)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    fun startConnection(onReady: () -> Unit) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    onReady()
                }
            }
            override fun onBillingServiceDisconnected() {
                // Try to reconnect with exponential backoff
            }
        })
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            // Acknowledge non-consumable items (like themes)
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        // Grant user the skin/feature
                    }
                }
            }
        }
    }
}
```

### Note on IME & Billing Flows
Since `BillingClient.launchBillingFlow` requires an `Activity` context, you **cannot** trigger billing directly from the `InputMethodService` context.
*   **Best Practice**: Redirect the user to a settings or shop Activity (e.g., `FlowboardSettingsActivity`) to make the purchase, and then read the purchase state in the IME using Shared Preferences, Database, or a secure repository.

## 2. Google Mobile Ads SDK (AdMob) Integration

### AdMob inside IME Keyboard Views
Display ads inside the keyboard view without violating Google Play policies (never overlay ads over standard input fields, and ensure ads do not block user typing).

#### Displaying a Banner Ad
Inflate AdView inside candidate suggestion bar or as a footer in settings:

```kotlin
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

fun setupBannerAd(adContainer: ViewGroup, adUnitId: String) {
    val adView = AdView(adContainer.context).apply {
        setAdSize(AdSize.BANNER)
        this.adUnitId = adUnitId
    }
    adContainer.removeAllViews()
    adContainer.addView(adView)
    
    val adRequest = AdRequest.Builder().build()
    adView.loadAd(adRequest)
}
```

### Compliance Warnings
> [!WARNING]
> **Ad Placement**: Do not place interstitial ads that trigger unexpectedly when the user is trying to type. This will result in accidental clicks and policy violations.
> Keep ads strictly inside settings/shop menus, or show small non-intrusive banner ads inside the candidate view bar when typing is paused.
> Make sure children consent options are handled correctly if Flowboard targets families.

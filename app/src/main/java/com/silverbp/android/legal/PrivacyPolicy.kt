package com.silverbp.android.legal

/**
 * Bumped whenever the privacy policy / terms text changes in a way that
 * requires renewed user consent. The onboarding gate compares this against
 * [com.silverbp.android.settings.UserSettings.acceptedPolicyVersion] — when
 * the stored value is lower the user is sent back to the consent step on
 * cold start. Settings exposes a "Review consent" button that resets the
 * stored value to 0 to manually re-show the consent flow.
 */
const val CURRENT_PRIVACY_POLICY_VERSION: Int = 2

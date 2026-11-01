package com.inteldes.app.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoginTest {
    @Test
    fun pkceMatchesTheRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun aNewVerifierIsLongUrlSafeAndDifferentEachTime() {
        val a = Pkce.newVerifier()
        val b = Pkce.newVerifier()
        assertTrue(a.length in 43..128)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(a == b)
    }

    @Test
    fun theStartUrlCarriesOnlyTheChallengeAndStateNeverTheVerifier() {
        val url = AppLoginLink.startUrl("https://inteldes.at-in.site/", "google", "CH", "ST")
        assertEquals("https://inteldes.at-in.site/auth/app/start?provider=google&challenge=CH&state=ST", url)
    }

    @Test
    fun aCallbackIsAcceptedOnlyWithTheStateThisPhoneStarted() {
        val code = "abcdefghijklmnopqrstuvwxyz012345"
        val ok = "inteldes://auth/callback?code=$code&state=S1"
        assertEquals(AppLoginCallback.Code(code), AppLoginLink.parse(ok, "S1"))
        assertNull(AppLoginLink.parse(ok, "OTHER"))                                   // forged or stale link
        assertNull(AppLoginLink.parse(ok, null))                                      // no login was started
        assertNull(AppLoginLink.parse("inteldes://record", "S1"))
        assertNull(AppLoginLink.parse("https://inteldes.at-in.site/auth/callback?code=$code&state=S1", "S1"))
        assertNull(AppLoginLink.parse("inteldes://auth/callback?code=pendek&state=S1", "S1"))     // not a code
        assertNull(AppLoginLink.parse("inteldes://auth/lain?code=$code&state=S1", "S1"))
    }

    @Test
    fun anErrorFromTheServerIsShownInsteadOfALogin() {
        val cb = AppLoginLink.parse("inteldes://auth/callback?error=Akun%20belum%20diundang&state=S1", "S1")
        assertEquals(AppLoginCallback.Error("Akun belum diundang"), cb)
    }

    @Test
    fun theButtonsNeedTheServerToFinishTheLoginForTheApp() {
        assertFalse(Providers(google = true, sso = true).external)                      // server cannot hand a login back yet
        assertTrue(Providers(google = true, appLogin = true).external)
        assertFalse(Providers(appLogin = true).external)                                // nothing to offer
    }
}

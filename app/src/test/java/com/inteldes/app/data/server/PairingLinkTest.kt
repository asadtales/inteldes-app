package com.inteldes.app.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
    private val server = "https://inteldes.at-in.site"
    private val code = "abcdefghijklmnopqrstuvwxyz012345"            // 32 chars, base64url alphabet
    private fun parse(raw: String) = PairingLink.parse(raw, server)

    @Test
    fun acceptsBothKindsFromOurServer() {
        val login = parse("https://inteldes.at-in.site/pair#c=$code&k=web_login")!!
        assertEquals(PairingLink.Kind.WEB_LOGIN, login.kind)
        assertEquals(code, login.code)
        assertEquals(PairingLink.Kind.ADD_PHONE, parse("https://inteldes.at-in.site/pair#c=$code&k=add_phone")!!.kind)
    }

    @Test
    fun acceptsTheExactStringTheRealServerHandsOut() {
        // Copied from a real POST /v1/auth/pair/start answer (an expired, throw-away code).
        val real = "https://inteldes.at-in.site/pair#c=rigyma0uFi4wQJ5Xsm9hsK7cgVf2FD5Y&k=web_login"
        val link = parse(real)!!
        assertEquals("rigyma0uFi4wQJ5Xsm9hsK7cgVf2FD5Y", link.code)
        assertEquals(PairingLink.Kind.WEB_LOGIN, link.kind)
    }

    @Test
    fun toleratesCaseOfHostExplicitPort443AndSurroundingSpaces() {
        assertNotNull(parse("  https://INTELDES.at-in.site/pair#c=$code&k=web_login \n"))
        assertNotNull(parse("https://inteldes.at-in.site:443/pair#c=$code&k=web_login"))
        assertNotNull(parse("https://inteldes.at-in.site/pair#k=web_login&c=$code"))     // parameter order doesn't matter
    }

    @Test
    fun rejectsEveryOtherHostOrLookAlike() {
        assertNull(parse("https://evil.com/pair#c=$code&k=web_login"))
        assertNull(parse("https://inteldes.at-in.site.evil.com/pair#c=$code&k=web_login"))
        assertNull(parse("https://evil.com/inteldes.at-in.site/pair#c=$code&k=web_login"))
        assertNull(parse("https://at-in.site/pair#c=$code&k=web_login"))
        assertNull(parse("https://www.inteldes.at-in.site/pair#c=$code&k=web_login"))
        assertNull(parse("https://inteldes.at-in.site@evil.com/pair#c=$code&k=web_login"))
    }

    @Test
    fun rejectsNonHttpsSchemes() {
        assertNull(parse("http://inteldes.at-in.site/pair#c=$code&k=web_login"))
        assertNull(parse("intent://inteldes.at-in.site/pair#c=$code&k=web_login"))
        assertNull(parse("javascript:alert(1)"))
        assertNull(parse("inteldes://record"))
    }

    @Test
    fun rejectsWrongPortPathOrMissingFragment() {
        assertNull(parse("https://inteldes.at-in.site:8443/pair#c=$code&k=web_login"))
        assertNull(parse("https://inteldes.at-in.site/login#c=$code&k=web_login"))
        assertNull(parse("https://inteldes.at-in.site/pair/x#c=$code&k=web_login"))
        assertNull(parse("https://inteldes.at-in.site/pair"))
        assertNull(parse("https://inteldes.at-in.site/pair?c=$code&k=web_login"))        // code in the query, not the fragment
    }

    @Test
    fun rejectsBadCodesAndUnknownKinds() {
        assertNull(parse("https://inteldes.at-in.site/pair#c=short&k=web_login"))
        assertNull(parse("https://inteldes.at-in.site/pair#c=${"a".repeat(81)}&k=web_login"))
        assertNull(parse("https://inteldes.at-in.site/pair#c=${code.dropLast(1)}!&k=web_login"))
        assertNull(parse("https://inteldes.at-in.site/pair#c=$code&k=admin"))
        assertNull(parse("https://inteldes.at-in.site/pair#c=$code"))
        assertNull(parse("https://inteldes.at-in.site/pair#k=web_login"))
    }

    @Test
    fun garbageIsNotAnError() {
        assertNull(parse(""))
        assertNull(parse("not a url at all"))
        assertNull(parse("https://"))
        assertNull(parse("\u0000"))
    }
}

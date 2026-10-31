/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.licence

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The licence is the only thing standing between "free" and "paid" in an app that cannot talk
 * to a server, so its failure modes are tested rather than assumed: a tampered payload, a key
 * minted for another phone, an expired subscription and plain garbage all have to land on their
 * own, specific answer.
 */
class LicenceTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val publicKeyBase64 = LicenceFormat.base64UrlEncode(keyPair.public.encoded)

    private val verifier = LicenceVerifier(publicKeyBase64)

    private val today = 20_000L

    private fun sign(payloadBase64: String): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyPair.private)
        signature.update(LicenceFormat.signedBytes(payloadBase64))
        return LicenceFormat.base64UrlEncode(signature.sign())
    }

    private fun mint(
        plan: Plan,
        device: String = "",
        issued: Long = today - 3,
        expires: Long = LicenceTerm.expiryFor(plan, issued),
        serial: String = "test-order",
    ): String {
        val licence = Licence(plan = plan.name, device = device, issuedEpochDay = issued, expiresEpochDay = expires, serial = serial)
        val payloadBase64 = LicenceFormat.base64UrlEncode(LicenceFormat.encode(licence).toByteArray(Charsets.UTF_8))
        return LicenceFormat.assemble(payloadBase64, sign(payloadBase64))
    }

    @Test
    fun `a correctly signed monthly licence unlocks pro and reports the days left`() {
        val device = LicenceVerifier.deviceTag("installation-1")
        val check = verifier.verify(mint(Plan.MONTHLY, device = device), device, today)
        assertTrue("expected a valid licence, got $check", check is LicenceCheck.Valid)
        val valid = check as LicenceCheck.Valid
        assertEquals(Plan.MONTHLY, valid.entitlements.plan)
        assertTrue(valid.entitlements.isPro)
        assertTrue(valid.entitlements.canExportMesh)
        assertFalse(valid.entitlements.pdfWatermark)
        assertEquals(Int.MAX_VALUE, valid.entitlements.maxProjects)
        // Issued three days ago for 31 days: 28 days remain.
        assertEquals(28L, valid.entitlements.daysLeft)
    }

    @Test
    fun `a lifetime licence never expires and has no day count`() {
        val check = verifier.verify(mint(Plan.LIFETIME), "anything", today)
        val valid = check as LicenceCheck.Valid
        assertEquals(Plan.LIFETIME, valid.entitlements.plan)
        assertTrue(valid.entitlements.isPro)
        assertNull(valid.entitlements.daysLeft)
        assertEquals(0L, valid.entitlements.expiresEpochDay)
    }

    @Test
    fun `a payload edited after signing is rejected`() {
        val genuine = mint(Plan.MONTHLY)
        val parts = genuine.split('.')
        val forgedPayload = LicenceFormat.encode(
            Licence(plan = Plan.LIFETIME.name, issuedEpochDay = today, expiresEpochDay = 0L),
        )
        val forged = LicenceFormat.assemble(
            LicenceFormat.base64UrlEncode(forgedPayload.toByteArray(Charsets.UTF_8)),
            parts[2],
        )
        assertEquals(LicenceCheck.BadSignature, verifier.verify(forged, "anything", today))
    }

    @Test
    fun `a licence signed by another key is rejected`() {
        val otherKey = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val payload = LicenceFormat.encode(Licence(plan = Plan.YEARLY.name, expiresEpochDay = today + 300))
        val payloadBase64 = LicenceFormat.base64UrlEncode(payload.toByteArray(Charsets.UTF_8))
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(otherKey.private)
            update(LicenceFormat.signedBytes(payloadBase64))
        }.sign()
        val forged = LicenceFormat.assemble(payloadBase64, LicenceFormat.base64UrlEncode(signature))
        assertEquals(LicenceCheck.BadSignature, verifier.verify(forged, "anything", today))
    }

    @Test
    fun `a licence bound to another device is refused, and the binding is case insensitive`() {
        val bound = "ABCDEF123456"
        assertEquals(LicenceCheck.DeviceMismatch, verifier.verify(mint(Plan.MONTHLY, device = bound), "999999999999", today))
        assertTrue(verifier.verify(mint(Plan.MONTHLY, device = bound.lowercase()), "abcdef123456", today) is LicenceCheck.Valid)
    }

    @Test
    fun `an expired subscription is reported as expired, not as invalid`() {
        val issued = today - 40
        val expired = mint(Plan.MONTHLY, issued = issued, expires = LicenceTerm.expiryFor(Plan.MONTHLY, issued))
        assertEquals(LicenceCheck.Expired, verifier.verify(expired, "anything", today))
    }

    @Test
    fun `garbage, a free-plan payload and an unknown plan are all malformed`() {
        assertEquals(LicenceCheck.Malformed, verifier.verify("", "d", today))
        assertEquals(LicenceCheck.Malformed, verifier.verify("hello", "d", today))
        assertEquals(LicenceCheck.Malformed, verifier.verify("CAR1.!!!.###", "d", today))
        assertEquals(LicenceCheck.Malformed, verifier.verify(mint(Plan.FREE), "d", today))
        assertEquals(LicenceCheck.Malformed, verifier.verify(mint(Plan.TRIAL), "d", today))
    }

    @Test
    fun `base64url round trips every byte value and rejects foreign characters`() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = LicenceFormat.base64UrlEncode(bytes)
        assertFalse("url-safe alphabet has no padding or plus", encoded.contains("=") || encoded.contains("+") || encoded.contains("/"))
        assertNotNull(LicenceFormat.base64UrlDecode(encoded))
        assertTrue(LicenceFormat.base64UrlDecode(encoded)!!.contentEquals(bytes))
        assertNull(LicenceFormat.base64UrlDecode("abc$def"))
        assertNull(LicenceFormat.base64UrlDecode(""))
        assertEquals("", LicenceFormat.base64UrlEncode(ByteArray(0)))
    }

    @Test
    fun `device tags are stable, short and contain no device identifier`() {
        val tag = LicenceVerifier.deviceTag(" 0d3f9c2a-1111-2222-3333-444455556666 ")
        assertEquals(12, tag.length)
        assertEquals(tag, LicenceVerifier.deviceTag("0d3f9c2a-1111-2222-3333-444455556666"))
        assertTrue(tag.all { it in '0'..'9' || it in 'A'..'F' })
        assertFalse(tag == LicenceVerifier.deviceTag("0d3f9c2a-1111-2222-3333-444455556667"))
    }

    @Test
    fun `epoch day ignores the local time zone`() {
        assertEquals(0L, LicenceVerifier.epochDay(0L))
        assertEquals(0L, LicenceVerifier.epochDay(86_399_999L))
        assertEquals(1L, LicenceVerifier.epochDay(86_400_000L))
        assertEquals(-1L, LicenceVerifier.epochDay(-1L))
    }

    @Test
    fun `free keeps one room and never blocks a measurement`() {
        val free = Entitlements.FREE
        assertFalse(free.isPro)
        assertEquals(1, free.maxProjects)
        assertTrue(free.allowsAnotherProject(0))
        assertFalse(free.allowsAnotherProject(1))
        assertTrue("the protocol PDF must always be produced", free.canExportPdf)
        assertTrue("the free PDF is watermarked", free.pdfWatermark)
        assertFalse("BIM export is the paid product", free.canExportMesh)
        assertTrue(free.canExportCsv)
        assertEquals(Int.MAX_VALUE, free.maxRoutesPerProject)
    }

    @Test
    fun `the three products carry the prices the business set`() {
        assertEquals(1_000, Product.MONTHLY.eurCents)
        assertEquals(10_000, Product.YEARLY.eurCents)
        assertEquals(99_900, Product.LIFETIME.eurCents)
        assertEquals(Plan.MONTHLY, Product.MONTHLY.plan)
        assertEquals(Plan.LIFETIME, Product.LIFETIME.plan)
        assertNull(Product.forPlan(Plan.FREE))
        assertNull(Product.forPlan(Plan.TRIAL))
    }

    @Test
    fun `a year costs less per month than twelve months, and the term is never short`() {
        val monthlyPerYear = Product.MONTHLY.eurCents * 12
        assertTrue(Product.YEARLY.eurCents < monthlyPerYear)
        // 31 days cover every calendar month, 366 days every year including 29 February.
        assertEquals(31L, LicenceTerm.daysFor(Plan.MONTHLY))
        assertEquals(366L, LicenceTerm.daysFor(Plan.YEARLY))
        assertEquals(0L, LicenceTerm.daysFor(Plan.LIFETIME))
        assertEquals(0L, LicenceTerm.expiryFor(Plan.LIFETIME, 20_000L))
        assertEquals(20_031L, LicenceTerm.expiryFor(Plan.MONTHLY, 20_000L))
    }

    @Test
    fun `the payment addresses are exactly the ones the business published`() {
        assertEquals("79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8", Coin.SOL.address)
        assertEquals("bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f", Coin.BTC.address)
        assertEquals("0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7", Coin.ETH.address)
        assertEquals("bitcoin:bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f", Coin.BTC.paymentUri)
        assertEquals(3, Coin.entries.size)
        assertEquals(3, Coin.entries.map { it.address }.toSet().size)
    }
}

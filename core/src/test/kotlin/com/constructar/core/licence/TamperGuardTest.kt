/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.licence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule in [ClockGuard], [LicenceSeal] and [EntitlementRules] is a way to get paid
 * features for free if it is wrong, so each one is pinned here.
 */
class TamperGuardTest {

    // ── ClockGuard ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the watermark follows a normal clock forward`() {
        val verdict = ClockGuard.evaluate(nowEpochDay = 20_000L, watermarkEpochDay = 19_990L)
        assertEquals(20_000L, verdict.effectiveEpochDay)
        assertEquals(20_000L, verdict.watermarkEpochDay)
        assertFalse(verdict.rolledBack)
    }

    @Test
    fun `a clock that goes back a year is a rollback and is evaluated at the watermark`() {
        val verdict = ClockGuard.evaluate(nowEpochDay = 19_600L, watermarkEpochDay = 20_000L)
        assertTrue(verdict.rolledBack)
        assertEquals(20_000L, verdict.effectiveEpochDay)
        assertEquals(20_000L, verdict.watermarkEpochDay)
    }

    @Test
    fun `one day of jitter is tolerated, two are not`() {
        assertFalse(ClockGuard.evaluate(19_999L, 20_000L).rolledBack)
        assertTrue(ClockGuard.evaluate(19_998L, 20_000L).rolledBack)
    }

    @Test
    fun `the watermark never decreases`() {
        var watermark = 0L
        listOf(100L, 90L, 95L, 300L, 250L).forEach { now ->
            watermark = ClockGuard.evaluate(now, watermark).watermarkEpochDay
        }
        assertEquals(300L, watermark)
    }

    @Test
    fun `a fresh installation has no watermark to violate`() {
        val verdict = ClockGuard.evaluate(nowEpochDay = 5L, watermarkEpochDay = 0L)
        assertFalse(verdict.rolledBack)
        assertEquals(5L, verdict.effectiveEpochDay)
    }

    // ── LicenceSeal ────────────────────────────────────────────────────────────────────

    @Test
    fun `a seal verifies only for its own value and installation`() {
        val seal = LicenceSeal.seal("install-1", "1234")
        assertTrue(LicenceSeal.matches("install-1", "1234", seal))
        assertFalse(LicenceSeal.matches("install-1", "1235", seal))
        assertFalse(LicenceSeal.matches("install-2", "1234", seal))
    }

    @Test
    fun `two seals differ and are plain uppercase hex`() {
        val a = LicenceSeal.seal("install-1", "1")
        val b = LicenceSeal.seal("install-1", "2")
        assertNotEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun `an absent or empty seal never matches`() {
        assertFalse(LicenceSeal.matches("install-1", "1", null))
        assertFalse(LicenceSeal.matches("install-1", "1", ""))
        assertFalse(LicenceSeal.matches("install-1", "1", "  "))
    }

    @Test
    fun `constant time comparison is still a comparison`() {
        assertTrue(LicenceSeal.constantTimeEquals("ABCD", "ABCD"))
        assertFalse(LicenceSeal.constantTimeEquals("ABCD", "ABCE"))
        assertFalse(LicenceSeal.constantTimeEquals("ABCD", "ABC"))
        assertTrue(LicenceSeal.constantTimeEquals("", ""))
    }

    // ── EntitlementRules ──────────────────────────────────────────────────────────────

    private val licenceYearly = Entitlements.of(
        plan = Plan.YEARLY,
        expiresEpochDay = 20_400L,
        todayEpochDay = 20_000L,
    )

    @Test
    fun `nothing bought, nothing started is the free plan`() {
        val out = EntitlementRules.decide(EntitlementRules.Input(nowEpochDay = 20_000L))
        assertEquals(Plan.FREE, out.entitlements.plan)
        assertTrue(out.trialAvailable)
        assertFalse(out.integrityFailed)
    }

    @Test
    fun `a verified licence wins over everything`() {
        val out = EntitlementRules.decide(
            EntitlementRules.Input(licence = licenceYearly, nowEpochDay = 20_000L, trialStartEpochDay = 19_999L),
        )
        assertEquals(Plan.YEARLY, out.entitlements.plan)
        assertEquals(400L, out.entitlements.daysLeft)
        assertFalse(out.trialAvailable)
    }

    @Test
    fun `a trial runs for exactly seven days and then stops`() {
        val start = 20_000L
        fun decideAt(day: Long) = EntitlementRules.decide(
            EntitlementRules.Input(trialStartEpochDay = start, watermarkEpochDay = day, nowEpochDay = day),
        )

        assertEquals(Plan.TRIAL, decideAt(start).entitlements.plan)
        assertEquals(Plan.TRIAL, decideAt(start + Entitlements.TRIAL_DAYS).entitlements.plan)
        assertEquals(0L, decideAt(start + Entitlements.TRIAL_DAYS).entitlements.daysLeft)
        assertEquals(Plan.FREE, decideAt(start + Entitlements.TRIAL_DAYS + 1).entitlements.plan)
        assertEquals(start + Entitlements.TRIAL_DAYS, decideAt(start).entitlements.expiresEpochDay)
    }

    @Test
    fun `a rollback does not resurrect an expired trial`() {
        // The trial ran out at 20 007; the device clock is set back to 20 001.
        val out = EntitlementRules.decide(
            EntitlementRules.Input(
                trialStartEpochDay = 20_000L,
                watermarkEpochDay = 20_100L,
                nowEpochDay = 20_001L,
            ),
        )
        assertTrue(out.clockRolledBack)
        assertEquals(Plan.FREE, out.entitlements.plan)
        assertFalse(out.trialAvailable)
        assertEquals(20_100L, out.effectiveEpochDay)
    }

    @Test
    fun `a rollback ends a trial that is still running`() {
        // Started three days ago, then the clock is set back ten days. The trial is judged at
        // the watermark instead - and because the clock went backwards, it is not honoured at
        // all: walking the clock back is the one thing that must never extend a trial.
        val out = EntitlementRules.decide(
            EntitlementRules.Input(
                trialStartEpochDay = 20_000L,
                watermarkEpochDay = 20_002L,
                nowEpochDay = 19_992L,
            ),
        )
        assertEquals(Plan.FREE, out.entitlements.plan)
        assertEquals(20_002L, out.effectiveEpochDay)
    }

    @Test
    fun `a clock set forward cannot start a trial in the future`() {
        // Trial "started" two years ahead, then the clock is corrected backwards.
        val out = EntitlementRules.decide(
            EntitlementRules.Input(
                trialStartEpochDay = 27_000L,
                watermarkEpochDay = 27_000L,
                nowEpochDay = 20_000L,
            ),
        )
        assertEquals(Plan.FREE, out.entitlements.plan)
        assertTrue(out.clockRolledBack)
    }

    @Test
    fun `a repackaged installation gets no paid plan even with a valid licence`() {
        val out = EntitlementRules.decide(
            EntitlementRules.Input(licence = licenceYearly, nowEpochDay = 20_000L, integrityOk = false),
        )
        assertTrue(out.integrityFailed)
        assertEquals(Plan.FREE, out.entitlements.plan)
        assertFalse(out.trialAvailable)
        // The data is still readable and exportable - that promise is not affected.
        assertTrue(out.entitlements.canExportPdf)
        assertTrue(out.entitlements.canExportCsv)
        assertFalse(out.entitlements.canExportMesh)
    }

    @Test
    fun `the watermark advances even when the licence is refused`() {
        val out = EntitlementRules.decide(
            EntitlementRules.Input(
                watermarkEpochDay = 19_000L,
                nowEpochDay = 20_000L,
                integrityOk = false,
            ),
        )
        assertEquals(20_000L, out.watermarkEpochDay)
    }

    @Test
    fun `an expiry in the past is not a live licence`() {
        val expired = Entitlements.of(plan = Plan.MONTHLY, expiresEpochDay = 19_990L, todayEpochDay = 20_000L)
        val out = EntitlementRules.decide(
            EntitlementRules.Input(licence = expired, nowEpochDay = 20_000L),
        )
        // `isPro` is still true for an expired paid plan. The rules re-check the expiry against
        // the effective day, so a store that was patched to hand over a stale licence grants
        // nothing at all.
        assertEquals(Plan.FREE, out.entitlements.plan)
        assertTrue(out.trialAvailable)
    }
}

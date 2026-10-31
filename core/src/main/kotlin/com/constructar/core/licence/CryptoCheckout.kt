/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.licence

/**
 * The three coins ConstructAR takes, and the single address each one is paid to.
 *
 * Crypto-only is a product decision, not an accident: it keeps the app free of any billing
 * SDK, of any Google/Apple account requirement and of any third party that could phone home —
 * which is the same reason the app has no INTERNET permission. The strings below are the
 * *only* payment endpoints in the product; there is no checkout server to compromise.
 */
enum class Coin(
    val symbol: String,
    val address: String,
    /** Deep-link scheme a wallet app understands, for the "open in wallet" button. */
    val uriScheme: String,
) {
    SOL(
        symbol = "SOL",
        address = "79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8",
        uriScheme = "solana",
    ),
    BTC(
        symbol = "BTC",
        address = "bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f",
        uriScheme = "bitcoin",
    ),
    ETH(
        symbol = "ETH",
        address = "0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7",
        uriScheme = "ethereum",
    ),
    ;

    /** `bitcoin:bc1q…`, `ethereum:0x…`, `solana:79Ks…` — opens the operator's wallet. */
    val paymentUri: String get() = "$uriScheme:$address"
}

/**
 * The three things an operator can buy. Prices are in euro cents so no floating point ever
 * touches money; formatting (and therefore the currency symbol's position) is the UI's job.
 *
 * EUR 10 per month, EUR 100 per year (two months free) and EUR 999 once — the lifetime price is
 * deliberately set at roughly the ten-year mark: it is for the one-person business that wants to
 * stop thinking about subscriptions, not a discount scheme.
 */
enum class Product(val plan: Plan, val eurCents: Int) {
    MONTHLY(Plan.MONTHLY, 1_000),
    YEARLY(Plan.YEARLY, 10_000),
    LIFETIME(Plan.LIFETIME, 99_900),
    ;

    val eur: Double get() = eurCents / 100.0

    companion object {
        fun forPlan(plan: Plan): Product? = entries.firstOrNull { it.plan == plan }
    }
}

/**
 * How long a minted licence lasts, per product.
 *
 * A month is 31 days and a year is 366: the licence is checked against a *day counter*, so a
 * fixed length in days must never be shorter than what the customer bought, or a February
 * purchase would expire a day early.
 */
object LicenceTerm {
    const val MONTH_DAYS: Long = 31L
    const val YEAR_DAYS: Long = 366L

    fun daysFor(plan: Plan): Long = when (plan) {
        Plan.MONTHLY -> MONTH_DAYS
        Plan.YEARLY -> YEAR_DAYS
        Plan.LIFETIME -> 0L
        Plan.FREE, Plan.TRIAL -> 0L
    }

    /** Last valid epoch day for a licence of [plan] issued on [issuedEpochDay]; `0` = never. */
    fun expiryFor(plan: Plan, issuedEpochDay: Long): Long {
        val days = daysFor(plan)
        return if (days <= 0L) 0L else issuedEpochDay + days
    }
}

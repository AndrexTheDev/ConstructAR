/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.diagnostics

import android.content.Context
import android.os.Build
import android.os.LocaleList
import com.constructar.app.BuildConfig
import com.constructar.core.diagnostics.DiagnosticsLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything a person would ask on the phone, written to files on the phone (finding 256).
 *
 * The reports from a test round were sentences: "the walls drift after ten minutes". Every round
 * trip then cost a day - which build, which phone, which screen, what the pose graph said - and
 * the log that answered half of it lived in `logcat`, which dies with the cable. This keeps a
 * session's worth on disk (`files/diagnostics/session-*.log`), writes a crash to its own file
 * (`crash-*.log`), and hands them over through the folder picker the app already uses for exports.
 *
 * **Nothing leaves the phone by itself.** There is no INTERNET permission in this app; a
 * diagnostics file is a file the tester saves and sends, exactly like a protocol.
 *
 * Every write is best-effort: a diagnostics path that throws is a diagnostics path that ends a
 * scan. The in-memory ring is the source of truth for the screen; the file is a copy for the
 * person reading it later.
 */
object Diagnostics {

    private val log = DiagnosticsLog()
    private var root: File? = null
    private var appContext: Context? = null
    private var session: File? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** The folder the tester's files live in, or `null` before [install]. */
    val directory: File? get() = root

    /** Call once, from the application, before anything else can fail. */
    fun install(context: Context) {
        appContext = context.applicationContext
        val dir = File(context.applicationContext.filesDir, "diagnostics")
        runCatching { dir.mkdirs() }
        root = dir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        session = File(dir, "session-$stamp.log")

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> crash(thread, error) }

        event("app", "started ${BuildConfig.VERSION_NAME} (${buildKind()})")
        event("device", "${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        // Which language the app resolved to, and which ones the phone offers (finding 260). The
        // first report of "the app is only in English" could not be answered from the file at all:
        // it said nothing about the one fact that decides the question.
        event("locale", localeLine())
    }

    /** Which of the three builds this is; the first question of every bug report. */
    fun buildKind(): String = when {
        BuildConfig.BETA_BUILD -> "beta"
        BuildConfig.DEBUG -> "debug"
        else -> "release"
    }

    /** One line, in memory and on disk. Safe to call from any thread, and before [install]. */
    fun event(tag: String, message: String) {
        log.add(tag, message)
        append(log.lines().lastOrNull().orEmpty())
    }

    /** The crash that ended the session, written before the process dies. */
    private fun crash(thread: Thread, error: Throwable) {
        event("crash", "${thread.name}: ${error.javaClass.name}: ${error.message}")
        error.stackTrace.take(MAX_STACK_FRAMES).forEach { frame -> event("crash", "  at $frame") }
        val target = root?.let { File(it, "crash-" + stamp() + ".log") }
        if (target != null) runCatching { target.writeText(log.toText(header())) }
        // The system's own handler still runs: this app does not swallow crashes, it only
        // remembers them.
        val previous = previousHandler
        if (previous != null && previous !== Thread.getDefaultUncaughtExceptionHandler()) {
            previous.uncaughtException(thread, error)
        }
    }

    /** The lines the diagnostics screen shows, newest last. */
    fun lines(): List<String> = log.lines()

    /** The whole session so far, as the text file the tester saves or pastes. */
    fun snapshot(): String = log.toText(header())

    /** Every file a tester should send, newest first, with the live session log included. */
    fun files(): List<File> {
        val dir = root ?: return emptyList()
        val listed = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        return listed
    }

    /** The header of every file this class writes: build, phone, session start. */
    private fun header(): List<String> = listOf(
        "ConstructAR " + BuildConfig.VERSION_NAME + " (" + buildKind() + ")",
        "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " - Android " + Build.VERSION.RELEASE +
            " (SDK " + Build.VERSION.SDK_INT + ")",
        "Session: " + stamp(),
        localeLine(),
    )

    /**
     * `app=` is what the app itself resolves (the per-app language if one was chosen), `device=` is
     * the phone's own list, first entry = the system language.
     */
    private fun localeLine(): String {
        val app = appContext?.resources?.configuration?.locales?.get(0)?.toLanguageTag()
        val device = LocaleList.getDefault().joinToString(",") { it.toLanguageTag() }
        return "Locale: app=" + (app ?: "?") + " device=" + device
    }

    private fun append(line: String) {
        val file = session ?: return
        runCatching { file.appendText(line + "\n") }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    /** A stack trace longer than this is noise in a file a person reads. */
    private const val MAX_STACK_FRAMES = 40
}

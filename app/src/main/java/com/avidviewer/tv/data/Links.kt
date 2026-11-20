package com.avidviewer.tv.data

/** Public links shown in the app (Settings › Updates & about). */
object Links {
    /** The user guide on the website (always the latest version; the PDF was discontinued Nov 15, 2026). */
    const val USER_GUIDE = "https://avidviewer.app/guide/"

    /** The Control4 universal mini driver (um_avid_viewer.c4z) on Google Drive, viewable by anyone with the link. */
    const val CONTROL4_DRIVER = "https://drive.google.com/file/d/10XxPywvX456lPTmou6xsqERmbTfhOJul/view"

    /**
     * The crash relay (tools/crash-relay/Code.gs, a Google Apps Script web app) that passes "Report issue"
     * crash reports on to avidviewer.app (it emailed them until Nov 20, 2026). Deployed from the avidviewer.app account (Nov 13, 2026).
     */
    const val CRASH_RELAY = "https://script.google.com/macros/s/AKfycbz2Tiz4sWy9Orxw5YeQ8FDKA5Zl3tKcQwu9IUlYIxlRoshPCgYeJItM8zU2HIi5sWHIZw/exec"
}

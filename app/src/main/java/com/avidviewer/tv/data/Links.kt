package com.avidviewer.tv.data

/** Public links shown in the app (Settings › Updates & about). */
object Links {
    /** The user guide on the website (always the latest version; the PDF was discontinued Nov 15, 2026). */
    const val USER_GUIDE = "https://avidviewer.app/guide/"

    /** How to get a free TMDB key, with what to enter in TMDB's form and copy buttons (Settings › Discover; Nov 25, 2026). */
    const val TMDB_HELP = "https://avidviewer.app/tmdb/"

    /** What to enter in TMDB's API key form (shown on the TV; the same as avidviewer.app/tmdb). */
    const val TMDB_APP_NAME = "Avid Viewer"
    const val TMDB_APP_URL = "https://avidviewer.app"
    const val TMDB_APP_SUMMARY = "Personal, non-commercial use. I use Avid Viewer, a TV app at home, to show movie and TV show details, posters, ratings and trending titles from TMDB on my own TVs. The key is only used on my own devices and isn't shared."

    /** The Control4 universal mini driver (um_avid_viewer.c4z) on Google Drive, viewable by anyone with the link. */
    const val CONTROL4_DRIVER = "https://drive.google.com/file/d/1W0G6EryHxrOq-dsFYn8BBdtXUAc87Wi0/view"

    /**
     * The crash relay (tools/crash-relay/Code.gs, a Google Apps Script web app) that passes "Report issue"
     * crash reports on to avidviewer.app (it emailed them until Nov 20, 2026). Deployed from the avidviewer.app account (Nov 13, 2026).
     */
    const val CRASH_RELAY = "https://script.google.com/macros/s/AKfycbz2Tiz4sWy9Orxw5YeQ8FDKA5Zl3tKcQwu9IUlYIxlRoshPCgYeJItM8zU2HIi5sWHIZw/exec"
}

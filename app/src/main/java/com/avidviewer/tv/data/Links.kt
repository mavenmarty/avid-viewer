package com.avidviewer.tv.data

/** Public links shown in the app (Settings › Updates & about). */
object Links {
    /** The user guide PDF; this address always serves the latest version. */
    const val USER_GUIDE = "https://github.com/mavenmarty/avid-viewer/releases/download/user-guide/AvidViewer-User-Guide.pdf"

    /** The Control4 universal mini driver (um_avid_viewer.c4z) on Google Drive, viewable by anyone with the link. */
    const val CONTROL4_DRIVER = "https://drive.google.com/file/d/10XxPywvX456lPTmou6xsqERmbTfhOJul/view"

    /**
     * The crash relay (tools/crash-relay/Code.gs, a Google Apps Script web app) that emails "Report issue"
     * crash reports to bugs@avidviewer.app. Empty until it is deployed: Report issue then says it couldn't send.
     */
    const val CRASH_RELAY = ""
}

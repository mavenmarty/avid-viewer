/**
 * Avid Viewer crash relay (Google Apps Script web app).
 *
 * The app's "Report issue" button (in the "Avid Viewer closed unexpectedly" dialog and in
 * Settings › Updates & about › Last crash) POSTs the saved crash report here as JSON; this script
 * emails it to bugs@avidviewer.app from the account that deployed it, so it lands in the usual
 * bug-report workflow. Fire TVs have no email app, and the app can't hold a mail password (the
 * repository is public), so this is the only piece that sends mail.
 *
 * Deploy (once, signed in to the avidviewer.app Google account):
 *   1. script.google.com › New project › paste this file as Code.gs › Save.
 *   2. Deploy › New deployment › type "Web app": Execute as "Me", Who has access "Anyone".
 *   3. Authorize (it needs "Send email as you"), then copy the web app URL (…/exec).
 *   4. Put that URL in Links.CRASH_RELAY (app/src/main/java/com/avidviewer/tv/data/Links.kt).
 * After editing this script, use Deploy › Manage deployments › Edit › New version, so the URL stays.
 *
 * What it accepts: {"app":"avid-viewer","version","device","crash","summary"} with size limits.
 * The app removes web addresses, user names and passwords from the crash text before sending.
 * At most 30 reports an hour are mailed (Apps Script also caps mail per day).
 */
var TO = 'bugs@avidviewer.app';
var MAX_PER_HOUR = 30;

function doPost(e) {
  try {
    var r = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    if (r.app !== 'avid-viewer') return reply_(400, 'unknown sender');
    var version = clip_(r.version, 40), device = clip_(r.device, 200);
    var crash = clip_(r.crash, 16000), summary = clip_(r.summary, 300);
    if (!version || !crash) return reply_(400, 'missing fields');

    var cache = CacheService.getScriptCache();
    var hour = 'sent-' + Utilities.formatDate(new Date(), 'UTC', 'yyyyMMddHH');
    var n = Number(cache.get(hour) || 0);
    if (n >= MAX_PER_HOUR) return reply_(429, 'too many reports');
    cache.put(hour, String(n + 1), 3600);

    var first = (summary || crash).split('\n')[0];
    MailApp.sendEmail({
      to: TO,
      subject: 'Avid Viewer crash report (' + version + ') · ' + first.slice(0, 120),
      body: 'Sent automatically from the app ("Report issue" after a crash).\n\n' +
        'App version: ' + version + '\n' +
        'Device: ' + device + '\n' +
        (summary ? 'Error: ' + summary + '\n' : '') +
        '\n--- Crash report ---\n' + crash,
      name: 'Avid Viewer crash reports',
    });
    return reply_(200, 'sent');
  } catch (err) {
    return reply_(500, String(err));
  }
}

function clip_(v, n) { return typeof v === 'string' ? v.slice(0, n) : ''; }

function reply_(code, message) {
  return ContentService.createTextOutput(JSON.stringify({ ok: code === 200, code: code, message: message }))
    .setMimeType(ContentService.MimeType.JSON);
}

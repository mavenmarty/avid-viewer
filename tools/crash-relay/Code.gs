/**
 * Avid Viewer crash relay (Google Apps Script web app).
 *
 * Nov 20, 2026: reports go to avidviewer.app only; nothing is emailed any more. Since 1.22.0 the app sends crash reports,
 * playback logs and security alerts straight to avidviewer.app (Admin › Reports). Versions before 1.22.0 still POST them
 * here, so this script passes each one on to https://avidviewer.app/api/tv/reports and answers with what the site said.
 * When the site doesn't take it, the answer is ok:false and the app keeps the report to send again later.
 *
 * Deploy (signed in to the avidviewer.app Google account):
 *   1. script.google.com › the crash relay project › replace Code.gs with this file.
 *   2. Project Settings › Show "appsscript.json" › replace it with appsscript.json from this folder
 *      (only the "Connect to an external service" permission is left; mail and Gmail are gone).
 *   3. Run doPost once in the editor and allow it, if asked.
 *   4. Deploy › Manage deployments › Edit › New version › Deploy, so the URL (Links.CRASH_RELAY) stays the same.
 *
 * What it accepts: {"app":"avid-viewer","version","device","crash","summary","kind"?} with size limits
 * ("kind":"playback" = the playback log, from 1.5.0; "kind":"security" = a freeRASP alert, from 1.11.0;
 * anything else is a crash report). The app removes web addresses, user names and passwords before sending.
 * At most 30 reports an hour are passed on.
 */
var SITE = 'https://avidviewer.app/api/tv/reports';
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

    // The site recognises the relay by its own id (AVR- and 8 hex digits), and a repeat of the same id isn't stored twice.
    var id = 'AVR-' + Utilities.getUuid().replace(/-/g, '').slice(0, 8);
    var kind = r.kind === 'playback' || r.kind === 'security' ? r.kind : 'crash';
    var res = UrlFetchApp.fetch(SITE, {
      method: 'post', contentType: 'application/json', muteHttpExceptions: true,
      payload: JSON.stringify({ app: 'avid-viewer', relay: true, id: id, kind: kind, version: version, device: device, summary: summary, crash: crash }),
    });
    var code = res.getResponseCode(), o = {};
    try { o = JSON.parse(res.getContentText() || '{}'); } catch (x) { o = {}; }
    if (code >= 200 && code < 300 && o.ok) return reply_(200, 'sent', o.id || id);
    return reply_(502, 'avidviewer.app answered ' + code);
  } catch (err) {
    return reply_(500, String(err));
  }
}

function clip_(v, n) { return typeof v === 'string' ? v.slice(0, n) : ''; }

function reply_(code, message, id) {
  var o = { ok: code === 200, code: code, message: message };
  if (id) o.id = id;
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

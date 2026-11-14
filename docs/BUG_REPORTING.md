# Bug reporting workflow

How bug reports sent to **bugs@avidviewer.app** become GitHub issues here, and how reporters are kept informed. Set up November 2026.

## Pieces

| Piece | When | What it does |
|---|---|---|
| **bugs@avidviewer.app** | always | Alias that delivers into the owner's mailbox (Google Workspace). The app's "Report a bug" sends here. |
| **Reporter emails** (scheduled Claude task) | hourly, 7:07 AM–10:07 PM CT, every day | Acknowledges new reports; emails reporters at every status change, with the fix version; posts reporter follow-ups to the issue; marks triaged reports read; moves finished threads to Trash (or archives them if deleting is refused). |
| **Bug digest** (scheduled Claude task) | weekdays 7:40 AM CT | Parses new reports into a numbered digest email; files the items the owner approves as issues here and links the emails to them. |
| **Daily issue check** (scheduled Claude task) | daily 8:59 AM CT | Reviews new or updated issues and reports likely causes and fixes. Changes nothing. |

## Owner's part

1. Read the morning digest ("AvidViewer bug digest – <date>").
2. Reply to it with `file 1`, `file 1, 3` or `file all`. The next morning run files them, or ask Claude in a chat to file right away.
3. Fix and release as usual. Close issues as **completed** (fixed) or **not planned**. Labels the reporter emails understand: `needs-info`, `in-progress`, `duplicate`.

Everything else is automatic.

## What reporters receive

All emails are replies in the reporter's own email thread, signed "The Avid Viewer team".

- **Acknowledgment** (within the hour): "We've received it", one line per report, and what extra details help.
- **Status updates**, one email per thread whenever something changes: Under review · Being worked on · We need a bit more information · Fixed, coming in the next update · **Fixed in Avid Viewer X** (or "Done in" for requests) · Closed · Reopened · Same as an earlier report. Released fixes also give the current version and how to update (Settings › Updates & about › Check now).
- Plain questions get no automatic email; the owner answers those.

## Privacy

- Issues filed from email say **Reporter: beta tester** only. Names and email addresses stay in the private mailbox and never appear in this repo.
- Reporter follow-ups are posted to issues without names, addresses, signatures or quoted email.

## How it keeps track (in Gmail)

- Label **AvidViewer Issues/#N** on a report email links it to issue #N (one email can have several).
- Label **AvidViewer Bugs/Acknowledged** = acknowledgment sent.
- Every automated email ends with `Ref: AV-<issue> · <status> · <version>` lines. The latest Ref line in a thread is what the reporter was last told, so a status is never sent twice.
- Labels **AvidViewer Bugs/Logged** (in a digest), **Not a bug**, **Filed** (digest whose items were filed), **Archived** (finished thread archived instead of deleted).

## Clean-up rules

- **Read:** a report is marked read once it's in a digest (triaged) or linked to an issue.
- **Deleted:** a report thread moves to Gmail's Trash (emptied after 30 days) once every report in it is linked to an issue, every issue is fixed in a released version, and the reporter has been told. Threads with an open, closed-not-fixed or unreleased issue, an unfiled report, or an unanswered reporter reply stay.
- **Archived instead:** if deleting a finished thread is refused, it is archived (out of the inbox, still in All Mail) and labeled **AvidViewer Bugs/Archived**, so it isn't retried. A reopened issue or a reporter reply brings it back into the process.
- Gmail filters can't see bug status, so these rules run in the hourly task rather than as Gmail filters.

## Fix versions

The fix version is the first release whose tag contains a commit mentioning `#N` (tag `v1.2.0.188` → "1.2.0"). Mention the issue number in fix commits (for example `fix: … (#9)`) so this works. Without one, the version named in the closing comment is used.

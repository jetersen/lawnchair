# Automatic backups

In Backup & restore, choose a shared-storage folder such as `backups/lawnchair`,
grant folder access, and enable automatic backups. The defaults are daily change
checks and 14 versions; weekly checks and 7 or 30 retained versions are also available.
Scheduled checks export only when the backed-up layout or settings differ from the
last successful export. They also export if the destination changes or the previous
file is missing. ZIP timestamps, previews, preference ordering, and database storage
bookkeeping do not count as changes. **Back up now** always creates a version.
Check the last-success time before relying on the schedule.

Exports contain the layout and settings, including folder covers. They omit
wallpaper and the visual preview. They use the normal `.lawnchairbackup` format
and can be restored through **Restore backup**. Folder permissions and scheduling
settings stay local to the installation and must be configured again on a new
installation.

New exports include a random installation identity. Restoring one in the same
installation can retain widget configuration when its widget IDs still belong to
this launcher and their providers and user profiles match. The identity is stored
outside all backups and is not imported. Older exports, exports from another
installation, and deleted or unbound widgets use the normal rebinding flow; an
export does not contain another app's private widget configuration. Restore stages
the archive before replacing the layout and updates the live preferences stores.

Configure Syncthing to sync the selected folder. Exporting does not require a
network connection. For a one-way phone-to-NAS setup, use send-only on the phone
and receive-only on the NAS. Ignore `*.lawnchairbackup.partial` on both sides.
Keep NAS versioning or a separate backup policy for recovery after synced deletions.

An export is written under a temporary name, closed, read back and verified, then
renamed before retention runs. Only matching exports from this installation are
pruned; manual exports, Nova backups, and exports from other installations are
left alone. A provider must support file creation, reading, writing, and renaming.
A failed or interrupted export does not trigger retention. Interrupted temporary
files can remain until removed manually.

Android schedules background work opportunistically. Low battery, low storage,
Doze, OEM restrictions, and force-stopping the app can delay exports. Reopen
Lawnchair after a force-stop. If folder access is lost, choose the folder again.
The settings screen reports export failures and retention failures separately.

## Tasker and adb intents

Choose the backup folder and enable **Allow automation intents** first. The opt-in
allows any app to request an export to that folder, at most once per minute.
Automation does not need the daily schedule enabled. Requests are serialized with
manual backups, and disabling the opt-in also blocks queued external requests.

For a changed-only check in the debug installation:

```sh
adb shell am broadcast -n app.lawnchair.debug/app.lawnchair.backup.BackupAutomationReceiver \
  -a app.lawnchair.action.BACKUP
```

Add `--ez force true` to create a version even without changes. The ordered
broadcast reports whether the request was queued or rejected; completion and
errors appear in Backup & restore. In Tasker, use **Send Intent**, action
`app.lawnchair.action.BACKUP`, package `app.lawnchair.debug`, class
`app.lawnchair.backup.BackupAutomationReceiver`, target **Broadcast Receiver**.
The optional Boolean extra is `force:true`.

To choose a backup and open its restore review, send an Activity intent with action
`app.lawnchair.action.RESTORE` and class `app.lawnchair.backup.BackupRestoreActivity`.
Without Data, this opens the document picker. The optional String extra `format`
is `lawnchair` (default) or `nova`:

```sh
adb shell am start -n app.lawnchair.debug/app.lawnchair.backup.BackupRestoreActivity \
  -a app.lawnchair.action.RESTORE --es format nova
```

Tasker can supply a readable `content://` URI as Data with a read permission grant.
ADB can supply `-d 'content://PROVIDER/DOCUMENT'` when Lawnchair already holds a
grant covering that document. The adb shell generally cannot grant access to an
arbitrary Storage Access Framework URI; use the picker in that case. Raw filesystem
paths are rejected. Restores always open the normal review screen and require
tapping Restore. The automation opt-in controls unattended exports;
opening a restore review does not require it. Use the installed package name in
place of `app.lawnchair.debug` for other build variants.

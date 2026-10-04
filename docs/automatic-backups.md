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

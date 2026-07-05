# SiShredder

A secure file eraser for Android, inspired by [Shreddit](https://apps.palmtronix.com/shreddit/android/).
Permanently destroys files by overwriting their contents with industry-standard
sanitization patterns before deletion, so they can't be recovered with undelete tools.

> **For security and research use only.** This project is provided strictly for
> lawful information-security, data-privacy, and research purposes. Data erasure
> is permanent and irreversible. The Software is provided with no warranty, and
> the author accepts no responsibility or liability for anything done with it:
> use is entirely at your own risk and your own legal responsibility. By using,
> building, or installing it you accept the full [DISCLAIMER](DISCLAIMER.md) and
> the [LICENSE](LICENSE). This is not legal advice.

## Features

- **File browser** for shared storage with image/video thumbnails, folder navigation,
  and multi-select (tap files to select, tap folders to open, select-all in the top bar).
- **Shredding schemes** (pick in Settings ⚙️):
  - Random (default): one pass of random data; correct on every kind of storage
  - Zeros (fast): one pass of zeros; equivalent on encrypted internal storage

  Multi-pass standards (DoD, Gutmann, …) are deliberately not offered: on flash
  storage they add wear and time but no security. If a single overwrite lands in
  place the data is gone; if the storage remaps writes, every extra pass lands in
  the same wrong place. Use the free-space wipe for remapped-write filesystems.
- **How shredding works**: each pass overwrites the file in place and fsyncs to disk;
  the file is then truncated to zero length, renamed to a random name (hiding the
  original name and size in directory metadata), and deleted. Folders are shredded
  recursively.
- **Free-space wipe**: fills all unallocated space on internal shared storage with
  random data and releases it, destroying remnants of files deleted before the app
  was installed.
- Confirmation dialogs, live progress with cancel, no background services, no network.

## Building

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run.

## Permissions

On Android 11+ the app asks for **All files access** (`MANAGE_EXTERNAL_STORAGE`)
so it can shred arbitrary user files. It uses no other permissions and never
touches the network.

## Honest caveats

On modern flash storage, wear-leveling and flash translation layers mean *no*
overwrite-based tool (including the original Shreddit) can give a 100% physical
guarantee that old data blocks are gone. Overwriting defeats ordinary recovery
apps; for the strongest protection combine it with Android's built-in
file-based encryption (enabled by default), so an attacker needs both the
leftover blocks *and* your keys.

Two smaller limitations, shared with tools like GNU `shred`:

- The rename-before-delete obscures the original filename best-effort, but
  filesystems don't scrub freed directory entries, so old name bytes can
  linger in directory blocks. File *contents* are unaffected.
- Copies made elsewhere (media-database thumbnails, app caches, cloud backups)
  are separate data that no shredder can reach.

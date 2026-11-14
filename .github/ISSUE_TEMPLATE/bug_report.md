---
name: Bug report
about: Report a problem with VoxelBrew and help us reproduce it.
title: ""
labels: ""
assignees: ""
---

## What happened?

Describe the problem and any error message you saw.

## Steps to reproduce

1. Open VoxelBrew or run this CLI command: …
2. …
3. …

**Expected result:**

**Actual result:**

**How often does it happen?** Always / sometimes / once

## Your setup

- VoxelBrew version (GUI: **Settings → About**; CLI: `cli --version`):
- Operating system and version:
- GUI or CLI:
- Installation method (installer, ZIP, or source build):
- Relevant plugins, slicer version, or printer model (if applicable):

## Logs and configuration

Please attach the log from the affected session and a copy of your configuration.
Both the GUI and CLI write to the same log file.

| File | Windows | Linux / macOS |
| --- | --- | --- |
| Current log | `%USERPROFILE%\.voxelbrew\log\app.log` | `~/.voxelbrew/log/app.log` |
| Configuration | `%USERPROFILE%\.voxelbrew\config.properties` | `~/.voxelbrew/config.properties` |

On Windows, paste `%USERPROFILE%\.voxelbrew` into File Explorer's address bar
and press Enter. On Linux, open `~/.voxelbrew` in your file manager (Ctrl+H
usually shows hidden folders). On macOS, use Finder's **Go → Go to Folder**
(Shift+Command+G) and enter `~/.voxelbrew`.

If the problem occurred in an earlier session, include the relevant `app-*.log`
archive from the `log` folder and the approximate time of the problem. If you
set `-Dvoxelbrew.home`, look in that folder instead; `-Dvoxelbrew.config`
overrides the configuration file location separately. If no log was created,
include any terminal output and mention that the log is missing.

Drag files into this issue's text box to attach them. If a file type is rejected,
attach a ZIP or rename a **copy** to `.txt`. Review copies before uploading and
remove passwords, API keys, private addresses, and other sensitive information;
this issue is public. Do not upload your entire configuration directory.

## Screenshots

For a visual problem, attach a screenshot showing the affected area and any
error message. Include enough of the window to show which workspace is open.

- **Windows:** press **Win+Shift+S**, select the area, then paste here with **Ctrl+V**.
- **Ubuntu (GNOME):** press **Print Screen**, choose an area or window, and capture
  it. Attach the saved image from **Pictures → Screenshots**. Other Linux desktops
  may use a different screenshot tool or save location.
- **macOS:** press **Shift+Command+4**, select the area, and attach the image saved
  to your desktop (or your configured screenshot location).

Crop or hide private information before attaching the screenshot. For CLI errors,
paste the command and output as text as well, so they can be copied and searched.

## Example file or additional context

If possible, attach a small `.vxb` document or input file that reproduces the
problem (ZIP it if needed). Only share files you are allowed to make public.
Mention any workaround or the last VoxelBrew version where this worked.

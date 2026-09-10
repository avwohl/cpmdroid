# Privacy Policy for CPMDroid

**Last Updated:** September 7, 2026

## Overview

CPMDroid is a CP/M emulator for Android. This privacy policy explains how the app handles user data.

## Data Collection

**CPMDroid does not collect, store, or transmit any personal information.** The app runs entirely on your device and does not:

- Collect personal identifiers
- Track your location
- Access your contacts, photos, or other personal files
- Use analytics or tracking services
- Display advertisements
- Require user accounts or registration

## Network Access

CPMDroid accesses the internet to download content from GitHub. No ROM and no disk image is bundled in the app, so the emulator ROM is downloaded like everything else. Specifically:

- **What is downloaded:**
  - The emulator ROM (about 512 KB) for the RomWBW release the app is set to
  - CP/M disk images
  - The catalog documents that list the ROMs and disk images on offer, and publish the size and SHA-256 each one is checked against
  - All three from the romwbw_disks releases area (https://github.com/avwohl/romwbw_disks/releases/)
  - Help documentation, from the same romwbw_disks releases area. Since 1.30 the topic list is part of that catalog index rather than a separate document in the CPMDroid releases area, so the app connects to one host and not two
- **When:** Only when you:
  - Start the emulator without the ROM for the selected release already on the device, and accept the download the app offers. This is what a first launch is: the app ships no ROM, so it cannot start until one catalog fetch has succeeded
  - Choose a different ROM or a different RomWBW release in Settings
  - Choose to download a disk image through the app's disk management interface
  - On first launch to download a default boot disk
  - Open the Help screen to view documentation
- **Data sent:** Standard HTTP requests containing only the file being requested

### Third-Party Services

When downloading the ROM, disk images, catalogs or help topics, your device connects directly to GitHub's servers. GitHub may log connection information according to their own privacy policy. This may include:

- Your IP address
- The files requested
- Timestamp of the request

CPMDroid has no access to or control over any data GitHub may collect. For information about GitHub's data practices, please refer to [GitHub's Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement).

## Local Storage

CPMDroid stores the following data locally on your device:

- The downloaded ROM and disk images (in app-specific storage), and, for the ROM, the size and SHA-256 the catalog published for it, so it can be re-checked on every later start without another catalog fetch
- User preferences such as font size, the selected RomWBW release and ROM, and disk slot assignments
- Emulator state during operation

This data remains on your device and is not transmitted anywhere.

## Children's Privacy

CPMDroid does not knowingly collect any information from children under 13 years of age. The app does not collect information from any users.

## Changes to This Policy

If we update this privacy policy, we will post the new policy here with an updated revision date.

## Contact

If you have questions about this privacy policy, you can contact us by opening an issue at:
https://github.com/avwohl/ioscpm/issues

## Summary

- **Data collected by CPMDroid:** None
- **Data shared with third parties:** None (though GitHub may log downloads per their policy)
- **Permissions used:** Internet access, for downloading the ROM, disk images and help documentation. The ROM download is not optional: the app ships no ROM, so a device that has never reached the network has nothing it is allowed to boot

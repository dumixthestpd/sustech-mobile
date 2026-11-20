# v0.3.30 — Blackboard materials and file sharing

Read a teacher's materials in SUSTech Mobile, share the original file to another app, or send it
straight to the campus print-upload screen.

- **Course materials and notices:** browse Blackboard's course-content tree, read notices,
  preview PDFs and images, and read the extracted text of DOCX handouts in the app.
- **Share actual files:** Share in an attachment row or its preview opens Android's chooser.
  Recipients receive the filename, type and readable file bytes. BB previews/downloads stay in memory.
- **Upload to PMS:** choose this target when sharing a supported file from BB or another app.
  The file is selected on the print-upload screen; review print settings and upload when ready.
  The initial incoming-share feature was contributed by
  [@yusancky](https://github.com/dumixthestpd/sustech-mobile/commit/3650b4b735d88318ab008fc449d2c8839d368e46).
- **More reliable handoffs:** keep large and actively previewed attachments readable; correct generic
  download types; import print files in the background without replacing another same-named file.
  Failed imports clear the selection and remove partial copies.
  A PDF-reader lifecycle deadlock found on the device is fixed as part of this handoff.
- **Assignment attachments:** the source additions since v0.3.29 also include picking a file from
  the phone for a Blackboard assignment, with confirmation before submission.
- **Developer builds:** GitHub Actions runs tests and provides a debug APK artifact, contributed by
  [@yusancky](https://github.com/dumixthestpd/sustech-mobile/pull/2). The release asset remains release-signed.

## Updating

Install over v0.3.23 or later to keep your data. Version code is now 33, so the update is distinguishable
from the v0.3.29 APK even if you previously installed a newer source build with the old version label.

## Limits

- DOCX previews show text, without original images, tables or layout. Legacy DOC and other
  unsupported preview types can be shared to a compatible app.
- The print share receiver accepts one PDF, DOC/DOCX, XLS/XLSX, JPEG, PNG or plain-text file at a time,
  up to 200 MB. It keeps a private temporary upload copy. Actual PMS uploads require campus network
  access; merely opening the upload screen does not.
- No live assignment was submitted and no real print job was created in this release's handoff
  verification. Live signed-in BB downloads and printer collection were not re-exercised.
- Personalized home screens and deadline notifications are separate concept demos, outside this APK.

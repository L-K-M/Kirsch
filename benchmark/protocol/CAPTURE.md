# Capture Protocol v0.1

## Purpose

Collect reproducible observations of the same physical print for the single-frame, PhotoScan, Kirsch RAW/YUV burst, and characterized flatbed baselines.

## Before Collection

- Assign the physical print and its related copies to a pre-split `source_group_id`.
- Record rights basis, finish, process if known, mounting/enclosure, geometry conditions, measured width/height, and measurement method.
- Inspect the print without cleaning or flattening it unless the treatment is recorded as a new comparison condition.
- Record the phone instance, OS build fingerprint, app build, camera ID, camera characteristics, and available RAW/manual/burst capabilities.
- Record the lighting setup, fixture state, geometry or setup photograph, diffuser/polarizer state, and measured lux/CCT/SPD when equipment is available. Use explicit missing reasons instead of zero.
- Place the independent verification target coplanar with the print when color, scale, or SFR is being measured. Do not use the profile-creation target for verification.

## Comparison Block

A comparison block fixes:

- physical print
- phone/scanner instance
- lighting and enclosure
- support surface and camera rig or handheld protocol
- verification target arrangement

Changing any item starts a new block. Within a block, randomize the acquisition order before capture.

## Repeat

A repeat requires lifting/repositioning the phone or resetting the physical setup as specified in the preregistration. Frames inside a burst are not repeats. Retain every attempt with its status and reason.

## Phone Burst

1. Enter the assigned print ID in the prototype.
2. Select RAW when supported; otherwise select YUV and record the fallback.
3. Frame the complete print and target with fixed zoom/crop.
4. Let preview 3A converge.
5. Trigger one five-frame burst. The prototype locks supported 3A controls and records actual results per frame.
6. Do not choose frames or retry based on the final image unless the protocol calls the first attempt failed. Keep failed/partial attempts.
7. Export the complete capture package, run `validate-capture`, and hash the accepted package into the benchmark manifest.

Do not claim synchronized, fixed-exposure, or fixed-focus capture unless the recorded per-frame results establish it.

## PhotoScan

- Record package/version, phone/OS, settings, observed workflow and frame count, export route/time, and whether source frames are available.
- Capture PhotoScan as a separate black-box acquisition. It does not consume the retained Kirsch burst.
- Preserve original export bytes and metadata before any transcoding.

## Flatbed Reference

- Capture a lossless, profiled 16-bits-per-channel master where supported.
- Record scanner instance/model, driver/software, declared sampling frequency, bit depth, profile, orientation, platen/contact state, and every automatic correction as explicitly on/off.
- Capture at 0 and 180 degrees where practical to reveal directional scanner artifacts.
- Treat the result as a characterized content reference, not perfect truth.

## Completion

- Verify file hashes and byte counts.
- Verify requested/received burst count and timestamp pairing.
- Record protocol deviations before looking at method scores.
- Copy confirmatory data into quarantined storage without inspecting outputs beyond integrity/eligibility checks allowed by the preregistration.

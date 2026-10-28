# Third-party source acknowledgements

## MiRearScreenSwitcher

The full-screen green liquid effect in `RearChargingLiquidView.kt` is adapted from
`LightningShapeView.java` by **AntiOblivionis**, part of
[MiRearScreenSwitcher](https://github.com/AntiOblivionis/MiRearScreenSwitcher),
revision `7e0ae1a35aea58c1f94019cf3cb54681adb54b9d`.

Original source:
`android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/LightningShapeView.java`.
The charging entry animation is described in `RearScreenChargingActivity.java`.

The referenced upstream version is licensed under **GNU GPL version 3**, compatible
with this project's GPL-3.0 license. The complete license is included in `LICENSE`.
Upstream credits 汐木泽 as chief tester and credits Cursor, Claude-4.5-Sonnet, GPT-5,
and Gemini-2.5-Pro as development assistants.

REAREye adapts the full-screen drawing to its existing host process, with a bounded
frame rate, visible-window lifecycle, display-relative gravity, safe text placement,
and live battery updates. MRSS's Shizuku task switching, launcher termination,
continuous wake loop, and foreground services are not included.

# Driver validation checklist

The first CI target is compile validation only. It does not launch SteamVR.

Manual runtime validation, on a PC with SteamVR installed:
1. Build the driver for the same OS and architecture as SteamVR.
2. Register the driver folder with SteamVR's `vrpathreg adddriver`.
3. Start SteamVR and inspect `vrserver.txt` / Web Console for the `endvr` provider.
4. Confirm the device list reports the EndVR prototype HMD.
5. Confirm the pose is valid but stationary.
6. Remove the driver using `vrpathreg removedriver` after the test.

Do not use this prototype as a safety-critical or room-scale headset.

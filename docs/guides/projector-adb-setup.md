# Projector adb setup

The relay controls the projector over adb, so the projector needs a little setup once.

## Network debugging

Turn on network debugging on the projector (Settings > network debugging). Then put its address in `.env` on the server as `PROJECTOR_ADB` (for example `192.168.1.5:5555`).

## Keep the wall's keyboard hidden while typing from the phone (planned)

> Not built. Tested on the Halo on 24 Oct 2026, and on its own this setting **does not hide the keyboard**.
>
> - **The virtual keyboard itself works:** Android accepts it, and its configuration switches from `nokeys` to `qwerty`. Text typed on it reaches fields.
> - **Gboard for TV (17.3.11 tv_release) shows anyway:** it ignores both this setting and the attached keyboard, even after a restart.
> - **Hiding the wall's keyboard therefore needs a different on-screen keyboard as the default**, one that respects a physical keyboard or draws nothing.

When a phone is connected, the relay's input helper (scrcpy) will plug a virtual USB keyboard into the projector, next to the virtual mouse it already uses. Android then hides its on-screen keyboard, so the wall stays as it is while you type on the phone.

This needs one setting on the projector, set once:

```bash
adb -s 192.168.1.5:5555 shell settings put secure show_ime_with_hard_keyboard 0
```

To check it:

```bash
adb -s 192.168.1.5:5555 shell settings get secure show_ime_with_hard_keyboard
```

It should print `0`.

- **Why it's safe to leave on:** the setting only matters while a keyboard is attached. When the relay is down, or the input helper has stopped after being idle, the virtual keyboard is gone. The projector then shows its normal on-screen keyboard and works with the XGIMI remote as usual.
- **Undo:**

  ```bash
  adb -s 192.168.1.5:5555 shell settings put secure show_ime_with_hard_keyboard 1
  ```

- **After a firmware update:** check the value again, in case the update reset it.

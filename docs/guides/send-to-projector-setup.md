# Sending links to the Shelf

## Before you start

The remote runs on the home server and has two addresses:

- **On your Wi-Fi:** `http://192.168.1.13:8790`. Nothing to install.
- **Through Tailscale** (if you turned on the add-on): `https://projector.<your-tailnet>.ts.net`. It works away from home, and it's the one to use for voice typing and for installing the remote as an app, because phones only allow those on https addresses.

The iPhone Shortcut and the Windows extension use the Wi-Fi address at home and fall back to Tailscale elsewhere. The Galaxy share sheet uses Tailscale only.

Anything you send lands in the Shelf, labelled with the device it came from. You'll find it:

- in **Theatre**: pull in from the right edge (the bar there turns amber when links are waiting)
- in the **Shelf** layout: the list on the main screen

Swipe a card right, or tap its arrow, to put it on the wall. Swipe left to remove it.

## iPhone: a Send to Projector shortcut

The iPhone can't add a website to its share sheet, so a Shortcut does it. You set it up once (about two minutes), and then **Send to Projector** shows up when you share from Safari, YouTube, or any other app.

1. Open the **Shortcuts** app, tap **+**, and name the shortcut `Send to Projector`.
2. Add the action **Get Network Details**. Leave it on **Wi-Fi** and **Network Name**.
3. Add **If**. Set the condition to **Network Details** **is** your home Wi-Fi's name.
4. Under **If**, add a **Text** action containing `http://192.168.1.13:8790`. Under **Otherwise**, add a **Text** action containing `https://projector.<your-tailnet>.ts.net`.
5. After **End If**, add **Get Contents of URL**. For its address, tap and pick **If Result**, then type `/shelf?from=iPhone` straight after it.
6. Tap the **›** on that action to show more options:
   - **Method**: `POST`
   - **Request Body**: `Form`
   - **Add new field** → **Text**. Key: `url`. Value: tap and pick **Shortcut Input**.
7. Picking **Shortcut Input** adds a line at the very top: **Receive Any input from Nowhere**. Tap **Nowhere** and turn on **Share Sheet**.
8. In that top line, tap **Any** and keep only **URLs**, **Safari web pages** and **Text**.
9. Optional: add a **Show Notification** action showing **Contents of URL**. You'll see "saved to the shelf" each time.
10. Tap **Done**.

At home it sends over Wi-Fi; anywhere else it goes through Tailscale. I haven't built this exact shortcut on an iPhone, so an action's name may differ slightly.

To use it, open any page or video, tap **Share**, then **Send to Projector**. If it isn't in the row, scroll to **Edit Actions** and add it to your favourites.

To use the remote itself, open its address in Safari, then tap **Share** and **Add to Home Screen**. It then opens full screen with its own icon. Use the Tailscale address for this if you want voice typing.

## Galaxy: Remote in the share sheet

This needs the Tailscale add-on. Once the remote is installed from Chrome, Android lists it in the share sheet like any app. Sharing to it saves the link and opens the Shelf.

1. Open **Chrome** (not Samsung Internet) and go to the **Tailscale** address. Android only offers **Install app** for https addresses, so the Wi-Fi address can't be installed.
2. Tap **⋮** → **Install app**. A **Remote** icon appears on your home screen. (Not yet checked on a Galaxy. Chrome on a PC confirms the site passes Chrome's install check, and Chrome shows **Install app** for sites that do.)
3. In any app, tap **Share** and pick **Remote**. The first time, it may be under **More**. Long-press it to pin it to the top.

It works the same folded or unfolded. If **Remote** doesn't appear in the share sheet, uninstall the icon and repeat step 2: Android only registers the share option during install.

## Phones: tap the notification to show it now

Once this is on, every link saved to the Shelf (from the iPhone Shortcut, the Galaxy share sheet or the extension) brings up a **Saved to the Shelf** notification on your phone. Tapping it puts the link on the wall and moves it off the Shelf, without opening the remote. If you leave it, the link just waits on the Shelf.

This needs the remote installed from the **Tailscale** address: on the iPhone with **Add to Home Screen** (iOS 16.4 or later), on the Galaxy with **Install app** as above.

1. Open the installed remote and tap the layouts button (top corner).
2. Tap **Shelf notifications** and allow notifications when the phone asks. It now reads **On**.

Tap it again to turn it off on that phone. If it says **Blocked**, allow notifications for Remote in the phone's settings. If you also use the Shortcut's own **Show Notification**, you'll get two notifications, so you can remove that action.

I tested the relay's side against a stand-in push service and Google's real one. I haven't had a notification arrive on an actual iPhone or Galaxy yet.

## Windows: the Send to Projector extension

The extension adds a **Send to Projector** button to the browser toolbar and a **Send to Projector** item when you right-click a page, a link, a video or selected text. A notification then names what was saved; click it to put the link on the wall. It works in Edge and Chrome.

1. Open `edge://extensions` (in Chrome, `chrome://extensions`).
2. Turn on **Developer mode**.
3. In the project's `browser-extension` folder, copy `config.example.js` to `config.js` and put your tailnet's name in the Tailscale address. The extension tries the Wi-Fi address first and falls back to Tailscale when it doesn't answer. Then click **Load unpacked** and pick that folder.
4. Pin it: click the puzzle-piece Extensions button in the toolbar, then the pin next to **Send to Projector**.

I checked this by loading the extension in Edge and triggering it: the link landed in the Shelf labelled Windows, and the notification read "Saved to the Shelf" with the link. I haven't seen the menus in your own browser. On another Windows PC, copy the folder over.

**Alternative without installing anything:** a bookmark with this as its address. Clicking it saves the page and briefly opens a tab that closes itself, which I checked in Edge.

```
javascript:void(window.open('http://192.168.1.13:8790/share-target?from=Windows&url='+encodeURIComponent(location.href)+'&title='+encodeURIComponent(document.title)))
```

## If something doesn't arrive

| What you see | What to do |
| --- | --- |
| Shortcut says it couldn't connect, or a page won't load | Check you're on the home Wi-Fi (or Tailscale is on), and that the server is running |
| "no link found" | What you shared had no web address in it. Share the page itself, not a photo or file |
| Remote missing from the Galaxy share sheet | Reinstall it from Chrome (step 2) |
| The bookmark does nothing | Your browser blocked the pop-up. Allow pop-ups for any site when it asks, then click again |

In the remote, the dot in the top-right corner is green when it's connected to the projector.

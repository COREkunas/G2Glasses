# How we got here

We started with an unboxing and a simple expectation: Halliday G2 looked like hardware that could become much more useful with the right software. The display, controls and connectivity suggested possibilities beyond the supplied apps. In practice, the features and customization available to us were limited. We could receive official updates, but we did not find a public, supported way to build and install our own G2 apps in the sources we checked. For us, that was disappointing.

Rather than stop at the companion app, we wanted to understand the device we owned.

## First, preserve the firmware

Our Windows tool reproduced the official update-check flow using the owner's existing account. It saved the offered **1.1.1 OTA package**, checked its size and SHA-256, and verified its archive contents. It did not install the update.

That distinction mattered: the glasses were still running **1.0.1/build 2018**. We kept the downloaded image and the installed firmware separate throughout the research. The OTA contained substantial component images and useful code, but not every part needed to recover an erased device.

## A custom phone app, then filesystem access

We reconstructed enough of the Bluetooth protocol to connect from our own Android app. Early commands were rejected until we used the glasses' existing account binding correctly. Then our own text appeared in the stock dashboard note card.

That was a useful first result, but sending text into an existing card was not the same as running our own app. Further analysis exposed the Wi-Fi and authenticated FTP path. We used it to inspect the filesystem and make verified backups, including two matching reads of the 16 MiB main NOR and critical configuration partitions. Those device-specific backups stay private.

## The charging connector could do more

The magnetic connector had four contacts, while our supplied cable passed power only. Adding data wires alone did not make Windows see a USB device. The firmware also had a configuration gate controlling USB startup.

After checking fresh backups, write behavior and complete readbacks, we changed that setting. A restart produced a USB composite device and a working ADB shell. The combination of cable work and firmware configuration opened a new route into the original system. This is a result on our unit, not a universal pinout or a safe recipe for every G2 revision.

## Our own screen on G2

The firmware contained a NuttX native module loader. We first ran a tiny marker module in RAM, then built **MINDYLAB**: our own display with a tap counter. We fixed duplicate counting and, later, stock input leaking into the Meeting screen behind our app.

The corrected app showed MINDYLAB, counted one tap at a time, and returned to the dashboard. We added phone Start/Stop controls over the authenticated Wi-Fi/FTP connection. Finally, a small startup-script patch loaded the additional module automatically after reboot. The owner confirmed that Start, taps and Stop worked with the glasses' USB cable disconnected.

This was a real additional native app, running alongside the original system. It did not replace a stock app or add an official dashboard icon. The public repository includes its source and the matching compiled module.

## What comes next

We have also built an experimental native navigation screen fed by Google Maps or Waze notifications on the phone. It is a step toward useful custom apps, not yet a fully validated navigation product.

The larger objective remains custom firmware with a reliable way back. ROM USB recovery has been harder than ordinary USB: enumeration, initialization, timeout cleanup and DMA ownership all matter. Offline models and RAM tests have provided useful evidence, but they do not yet prove recovery when stock firmware cannot boot.

We can run custom apps and have tested a narrow persistent stock-firmware change. We cannot honestly promise a safe complete reflash or guaranteed unbrick. Solving that gap is the next major milestone toward full functionality, greater privacy and control over our own glasses.

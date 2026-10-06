RiderRouteAI V0.5.0 - Termux Build

After extracting this folder into the cloned repository:

  cd ~/RiderRouteAI
  git add -A
  git commit -m "Add RiderRouteAI V0.5.0"
  git push -u origin main

Local APK build:
  gradle assembleDebug
  cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/RiderRouteAI-debug.apk

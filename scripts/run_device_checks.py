"""Run device checks without Gradle uninstalling the app before screenshot export."""
import pathlib
import re
import subprocess

out = pathlib.Path('app/build')
subprocess.run(['adb', 'install', '-r', str(out / 'outputs/apk/debug/app-debug.apk')], check=True)
subprocess.run(['adb', 'install', '-r', str(out / 'outputs/apk/androidTest/debug/app-debug-androidTest.apk')], check=True)
result = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-r',
    'com.example.parallaxwallpaper.test/androidx.test.runner.AndroidJUnitRunner'],
    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
print(result.stdout)
(out / 'device-test.log').write_text(result.stdout)
subprocess.run(['adb', 'pull', '/sdcard/Android/data/com.example.parallaxwallpaper/files',
    str(out / 'device-screenshots')], check=True)
assert result.returncode == 0 and re.search(r'OK \(1 test\)', result.stdout), 'Android instrumentation tests failed'
assert len(list((out / 'device-screenshots').glob('*.png'))) >= 3, 'Missing UI screenshots'

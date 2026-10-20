"""Exercise the release codec and optionally the real system share chooser on our emulator."""
import subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
adb=r'C:\Users\15072\Desktop\TIM\test\toolchain\sdk\platform-tools\adb.exe'
prefix=[adb,'-s','emulator-5554']
subprocess.run(prefix+['install','--no-streaming','-r',str(root/'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')],check=True)
result=subprocess.run(prefix+['shell','am','instrument','-w','-r','-e','class','com.example.parallaxwallpaper.VideoExportTest',
    'com.example.parallaxwallpaper.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=240)
out=root/'qa/output';out.mkdir(exist_ok=True)
(out/'export-device.log').write_text(result.stdout+result.stderr,encoding='utf8')
print(result.stdout)
subprocess.run(prefix+['pull','/sdcard/Android/data/com.example.parallaxwallpaper/files/export-sample.mp4',str(out/'export-sample.mp4')],check=True)
assert result.returncode==0 and 'OK (1 test)' in result.stdout, 'See qa/output/export-device.log'

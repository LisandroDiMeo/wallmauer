import subprocess
import os

dirs = os.listdir()

for directory in dirs:
	if os.path.isdir(directory):
		install_cmd = [
			"adb", "install", "-g", f"{directory}/{directory}.apk"
		]
		subprocess.run(install_cmd)

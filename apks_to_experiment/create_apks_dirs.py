import os
import shutil

# Get the current directory
current_directory = os.getcwd()

# List all files with the .apk extension in the current directory
apk_files = [file for file in os.listdir(current_directory) if file.endswith('.apk')]

# Create a directory for each APK file and move the file into its directory
for apk_file in apk_files:
    # Extract the filename without extension
    apk_name = os.path.splitext(apk_file)[0]
    
    # Create a directory with the apk_name if it doesn't exist
    apk_directory = os.path.join(current_directory, apk_name)
    if not os.path.exists(apk_directory):
        os.makedirs(apk_directory)
    
    # Move the APK file to its directory
    shutil.move(apk_file, os.path.join(apk_directory, apk_file))

print("APK files organized into directories.")

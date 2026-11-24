# YanHH3D CloudStream Add-on

CloudStream provider for **https://yanhh3d.ee**. This is a CloudStream extension project, separate from the Android APK project.

## Features
- Browse the site's recent anime catalog
- Search titles
- Open title details and episode lists
- Resolve ordinary video pages, iframe players and direct video sources through CloudStream extractors

## Install in CloudStream
After the first successful GitHub Actions build, add this repository in CloudStream:

`https://raw.githubusercontent.com/thuogla8196-gif/yanhh3d--cloud-stream/builds/repo.json`

## Build
The workflow in `.github/workflows/build.yml` builds the `.cs3` plugin and publishes repository files to the `builds` branch whenever code is pushed to `main`.

If the workflow cannot push the build branch, open **Settings → Actions → General → Workflow permissions** in GitHub and enable **Read and write permissions**.

## Notes
The provider only delegates normal iframe/video playback URLs to CloudStream extractors. It does not bypass DRM, logins, or other access controls.

---
name: github-actions
description: Use when a task needs a heavy build, long compile, large test suite, Docker image, Android or desktop app build, or anything slow on a phone; run it on GitHub Actions instead and bring back the result.
---

# Heavy work on GitHub Actions

You run inside a phone. Builds here are slow and drain the battery, so send heavy work to GitHub Actions, which gives public repositories free compute and private ones a monthly allowance. Only push to repositories the user named or agreed to.

## Sign in once

Check `gh auth status`. If not signed in, run `gh auth login --hostname github.com --git-protocol https --web`. It prints a one-time code: show the code to the user and ask them to open https://github.com/login/device on their phone and enter it. Then run `gh auth setup-git` so `git push` uses the same sign-in.

## Run a build

1. Make sure the repository has a workflow with a `workflow_dispatch` trigger. If not, add `.github/workflows/phone-build.yml` (template below), adjusted to the project's build command and output path, commit it, and push. The token needs the `workflow` scope to push workflow files; if the push is refused for that reason, run `gh auth refresh --scopes workflow` and repeat the device-code step.
2. Start it: `gh workflow run phone-build.yml --ref <branch>`.
3. Find the run: `gh run list --workflow phone-build.yml --limit 1`, then follow it with `gh run watch <run-id> --exit-status`.
4. Fetch results: `gh run download <run-id> --dir build-output`. On failure, read `gh run view <run-id> --log-failed` and fix the cause before retrying.
5. Tell the user where the result is: the downloaded path, and the run's web page from `gh run view <run-id> --json url --jq .url`, where they can also download the artifact on the phone.

## Template

```yaml
name: Phone build
on:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      # Replace with the project's toolchain setup and build command.
      - uses: actions/setup-node@v4
        with:
          node-version: 24
      - run: npm ci && npm run build
      - uses: actions/upload-artifact@v4
        with:
          name: build-output
          path: dist/
```

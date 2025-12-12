This directory includes files that will be bundled into the final jDeploy package when you build and deploy your application. You can place additional resources, configuration files, or any other necessary assets here to ensure they are included in the deployment package.

NOTE: You will still need to specify each file to be included in the package.json under `jdeploy.files`.


## About jdeploy-prelaunch.jar

The source for jdeploy-prelaunch.jar is available at: https://github.com/shannah/brokk-jdeploy-prelaunch/releases/tag/v0.0.1

This jar file is executed by the jdeploy launcher before your main application starts. This particular jar file checks the current launcher version, and if it is older than the one that came with brokk 0.17.3, then it will prompt the user to update their launcher to the latest version.  

Launcher versions bundled with versions older than 0.17.3 don't support Java 25 properly - but this launcher will still successfully run.  This is not necessary for newer launchers, since the launchers bundled with 0.17.3 and newer already include native checks on launch to ensure that the launcher is up to date.  The package.json's jdeploy.minLauncherInitialAppVersion directive is used to control this setting.
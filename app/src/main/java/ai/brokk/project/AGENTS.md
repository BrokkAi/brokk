# Project Build Settings
1. Use IProject's `BuildAgent.BuildDetails awaitBuildDetails()` method. The similarly named "load" method is for internal use only.
1. Most test code should use TestProject. If you have a good reason to use MainProject instead, it's your responsibility
   to call MP::setBuildDetails() before calling awaitBuildDetails, or await will never return.

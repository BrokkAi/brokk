# Project Build Settings
1. Use IProject's `BuildAgent.BuildDetails awaitBuildDetails()` method. The similarly named "load" method is for internal use only.
1. Most test code should use TestProject. If you have a good reason to use MainProject instead, use the static MainProject.forTests
   to acquire an instance, which will set BuildDetails to EMPTY instead of leaving you with a future that will never complete.

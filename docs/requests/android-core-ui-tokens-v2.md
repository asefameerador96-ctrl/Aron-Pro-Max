# Request: tokens v2 is not in the repository
Date 2026-10-07. The recycle brief says `docs/design/tokens.md` is a v2, but INT (daafcaf7) and every branch I can fetch hold only v1 (title "v1", 41 KB; `git log --all -- docs/design/tokens.md` ends at 381e7a4a). Porting v2 into `Tokens.kt`, the `TokenContrastTest` pairs and the 13 golden re-record are blocked until the v2 file is pushed to INT or a lane branch. Please push it or name the branch.

**Lead 2026-10-07 16:05 UTC: CLOSED.** The tokens-v2 port and re-recorded goldens are already on lane/android-core-ui (commits 82ed0724, 67e7fd53); nothing further to apply.

# Size rules of the AMO release build (the APK size gate, docs/31). Behaviour is unchanged.
# Kotlin's parameter null checks guard Java callers that do not exist in this app: remove the calls (R8 full mode).
-processkotlinnullchecks remove
# One flat package for the obfuscated classes: a shorter string table in the dex.
-repackageclasses ''

# core-system consumer R8 rules (android-sys). WorkManager and the system installer create these by class name:
-keep class com.aktcl.aron.core.system.support.SupportWorker { <init>(...); }
-keep class com.aktcl.aron.core.system.update.InstallStatusReceiver { <init>(); }

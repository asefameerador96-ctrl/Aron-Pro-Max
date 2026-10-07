# core-media consumer R8 rules (android-sys). kotlinx.serialization ships its own rules for the @Serializable queue items;
# CameraX and WorkManager ship theirs. MediaWorker is created by WorkManager by class name:
-keep class com.aktcl.aron.core.media.MediaWorker { <init>(...); }

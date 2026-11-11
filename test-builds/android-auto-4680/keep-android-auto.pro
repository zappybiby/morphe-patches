-dontobfuscate
-dontoptimize
-keepattributes InnerClasses,EnclosingMethod,Signature,*Annotation*
-keep class app.morphe.patches.music.misc.androidauto.AndroidAutoPatchKt {
    public static app.morphe.patcher.patch.Patch getAndroidAutoPatch();
}

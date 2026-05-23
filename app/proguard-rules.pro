# PATH: nw-child-app/app/proguard-rules.pro
-keep class com.nw.childapp.data.** { *; }
-keep class com.nw.childapp.service.** { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keepattributes Signature
-keepattributes *Annotation*